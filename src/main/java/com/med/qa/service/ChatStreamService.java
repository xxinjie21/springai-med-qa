package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.memory.SessionCoordinate;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRagAdvisorFactory;
import com.med.qa.rag.MedRetrievalQuery;
import com.med.qa.security.MedCallerScope;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.PatientAccessGuard;
import com.med.qa.security.RequestIdentityGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Orchestrates one streaming consultation turn.
 *
 * <p>It assembles the official Spring AI {@link ChatClient} prompt for a consultation: the patient's
 * question, the conversation id that binds the turn to its {@link SessionCoordinate} window (so the
 * memory advisor loads and persists through the two-tier repository), and a per-scope
 * {@link MedRagAdvisorFactory RAG advisor} that recalls only the documents the caller is entitled to.
 * No similarity scoring, Top-K selection, vector math or prompt stitching is performed here — those
 * belong to the official advisor and vector store. The method returns the streamed content as a
 * {@link Flux} that the SSE layer flushes to the client chunk by chunk.</p>
 *
 * <h2>Authorization runs before the model (D41)</h2>
 * <p>The turn's tenant / department / patient are taken from the authenticated {@link MedPrincipal},
 * never from the request body: the body's identity fields are passed to
 * {@link RequestIdentityGuard}, which refuses the request with {@link ErrorCode#FORBIDDEN} when they
 * contradict the principal. Before D41 this method used the body values directly, so a caller could
 * stream against another patient's session by naming that patient.</p>
 *
 * <p>Three checks then run, in this order, before anything else happens:</p>
 * <ol>
 *   <li>{@link RequestIdentityGuard#resolve} — the effective scope, or a refusal;</li>
 *   <li>{@link PatientAccessGuard#assertScope} — the caller may act inside that scope at all. This is
 *       the same scope policy the session-creation path uses; calling it explicitly here (rather than
 *       relying only on the row-level check the session service performs) keeps the streaming path's
 *       authorization legible and directly assertable, and is defence in depth should the session
 *       lookup ever be bypassed;</li>
 *   <li>{@link MedChatSessionService#requireWritableSession} — the session exists, belongs to the
 *       caller, and still accepts messages. A closed or archived transcript must never grow, and before
 *       D41 this method had no production caller at all despite the javadoc claiming the streaming path
 *       invoked it.</li>
 * </ol>
 *
 * <p>Only after all three pass is the chat model consulted, so no refusal ever reaches — or pays for —
 * an LLM call. The checks are synchronous, which also means they observe the request thread's
 * security context and cannot race with the asynchronous stream.</p>
 *
 * <h2>Fail closed on configuration</h2>
 * <p>When no usable {@link ChatClient.Builder} can be resolved (the deployment has not enabled
 * {@code spring.ai.model.chat}), the turn is rejected with {@link ErrorCode#LLM_SERVICE_ERROR} instead
 * of starting a stream that could never complete. Both shapes of "no model" are handled — an absent
 * builder bean and a builder bean whose creation fails for want of a {@code ChatModel}; see
 * {@link #resolveChatClientBuilder()}. A malformed scope/retrieval combination surfaces as an
 * {@link IllegalArgumentException} (a caller error) for the controller to map onto a bad request.</p>
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    private final MedRagAdvisorFactory ragAdvisorFactory;

    private final RequestIdentityGuard identityGuard;

    private final PatientAccessGuard accessGuard;

    private final MedChatSessionService sessionService;

    /**
     * Creates the streaming orchestration service.
     *
     * @param chatClientBuilder provider of the official chat client builder, must not be {@code null}
     * @param ragAdvisorFactory factory of the per-scope RAG advisor, must not be {@code null}
     * @param identityGuard     reconciler of the authenticated principal and the body's identity claims,
     *                          must not be {@code null}
     * @param accessGuard       scope-level ownership guard, must not be {@code null}
     * @param sessionService    session lifecycle service, consulted for the writability check, must not be
     *                          {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public ChatStreamService(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                             MedRagAdvisorFactory ragAdvisorFactory,
                             RequestIdentityGuard identityGuard,
                             PatientAccessGuard accessGuard,
                             MedChatSessionService sessionService) {
        this.chatClientBuilder = Objects.requireNonNull(chatClientBuilder, "chatClientBuilder must not be null");
        this.ragAdvisorFactory = Objects.requireNonNull(ragAdvisorFactory, "ragAdvisorFactory must not be null");
        this.identityGuard = Objects.requireNonNull(identityGuard, "identityGuard must not be null");
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard must not be null");
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
    }

    /**
     * Builds the streaming consultation for one request.
     *
     * @param request   consultation request, must not be {@code null}
     * @param principal the authenticated caller, or {@code null} for an anonymous request
     * @return the streamed assistant text, never {@code null}
     * @throws NullPointerException     when {@code request} is {@code null}
     * @throws BizException             {@link ErrorCode#FORBIDDEN} when the caller is anonymous or the
     *                                  request claims an identity it is not authenticated for;
     *                                  {@link ErrorCode#NOT_FOUND} when the session does not exist in the
     *                                  caller's scope; {@link ErrorCode#BAD_REQUEST} when the session is
     *                                  closed or archived; {@link ErrorCode#LLM_SERVICE_ERROR} when no
     *                                  chat model is configured
     * @throws IllegalArgumentException when the scope/retrieval combination is invalid (caller error)
     */
    public Flux<String> streamConsultation(ChatStreamRequest request, @Nullable MedPrincipal principal) {
        Objects.requireNonNull(request, "request must not be null");
        MedCallerScope scope = identityGuard.resolve(
                principal, request.tenant(), request.dept(), request.patientId());
        accessGuard.assertScope(principal, scope.tenantId(), scope.deptId(), scope.patientId());
        sessionService.requireWritableSession(scope.tenantId(), scope.deptId(), request.session());

        ChatClient.Builder builder = resolveChatClientBuilder();
        ChatClient chatClient = builder.build();
        SessionCoordinate coordinate =
                SessionCoordinate.of(scope.tenantId(), scope.deptId(), request.session());
        MedDocumentScope documentScope = toDocumentScope(scope);
        boolean includeShared =
                request.includeSharedDocuments() == null || request.includeSharedDocuments();

        MedRetrievalQuery.Builder queryBuilder =
                MedRetrievalQuery.builder(request.message(), documentScope)
                        .includeSharedDocuments(includeShared);
        if (request.topK() != null) {
            queryBuilder.topK(request.topK());
        }
        MedRetrievalQuery query = queryBuilder.build();

        Advisor ragAdvisor = ragAdvisorFactory.createAdvisor(query);
        log.debug("streaming consultation for session {}", coordinate.toConversationId());
        return chatClient.prompt()
                .user(request.message())
                .advisors(advisorSpec ->
                        advisorSpec.param(ChatMemory.CONVERSATION_ID, coordinate.toConversationId()))
                .advisors(ragAdvisor)
                .stream()
                .content();
    }

    /**
     * Resolves the chat client builder, reporting a missing model as a business error.
     *
     * <p>Two distinct ways of "having no model" have to be folded into one answer:</p>
     * <ul>
     *   <li>no builder bean at all — {@code getIfAvailable()} returns {@code null};</li>
     *   <li>the builder bean exists but cannot be created, because Spring AI's auto-configuration
     *       declares it with a mandatory {@link org.springframework.ai.chat.model.ChatModel} parameter
     *       and {@code spring.ai.model.chat} is off. An {@link ObjectProvider} does <em>not</em> swallow
     *       an instantiation failure, so this surfaces as a {@link BeansException}.</li>
     * </ul>
     *
     * <p>The second case is the one a misconfigured deployment actually hits. Without this catch it
     * escaped as a raw bean-creation stack trace and a {@code 500}, contradicting the documented
     * contract that a deployment without a model answers with a precise
     * {@link ErrorCode#LLM_SERVICE_ERROR}. Found by the D41 cross-component contract test, which is the
     * only test that drives the real {@code ObjectProvider} rather than a mocked one.</p>
     *
     * @return the configured builder, never {@code null}
     * @throws BizException {@link ErrorCode#LLM_SERVICE_ERROR} when no usable builder can be resolved
     */
    private ChatClient.Builder resolveChatClientBuilder() {
        ChatClient.Builder builder;
        try {
            builder = chatClientBuilder.getIfAvailable();
        } catch (BeansException ex) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat client not configured; enable spring.ai.model.chat and provide an API key", ex);
        }
        if (builder == null) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat client not configured; enable spring.ai.model.chat and provide an API key");
        }
        return builder;
    }

    /**
     * Turns the caller's effective identity into the RAG isolation scope.
     *
     * @param scope the resolved caller scope, must not be {@code null}
     * @return a patient-scoped scope when the caller acts for one patient, otherwise a department-wide one
     */
    private static MedDocumentScope toDocumentScope(MedCallerScope scope) {
        return scope.isPatientScoped()
                ? MedDocumentScope.ofPatient(scope.tenantId(), scope.deptId(), scope.patientId())
                : MedDocumentScope.ofDepartment(scope.tenantId(), scope.deptId());
    }
}
