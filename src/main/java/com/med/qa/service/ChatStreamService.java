package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.memory.SessionCoordinate;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRagAdvisorFactory;
import com.med.qa.rag.MedRetrievalQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
 * <h2>Fail closed on configuration</h2>
 * <p>When no {@link ChatClient.Builder} is wired (the deployment has not enabled
 * {@code spring.ai.model.chat}), the turn is rejected with {@link ErrorCode#LLM_SERVICE_ERROR} instead
 * of starting a stream that could never complete. A malformed scope/retrieval combination surfaces as
 * an {@link IllegalArgumentException} (a caller error) for the controller to map onto a bad request.</p>
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;

    private final MedRagAdvisorFactory ragAdvisorFactory;

    /**
     * Creates the streaming orchestration service.
     *
     * @param chatClientBuilder provider of the official chat client builder, must not be {@code null}
     * @param ragAdvisorFactory  factory of the per-scope RAG advisor, must not be {@code null}
     * @throws NullPointerException if an argument is {@code null}
     */
    public ChatStreamService(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                             MedRagAdvisorFactory ragAdvisorFactory) {
        org.springframework.util.Assert.notNull(chatClientBuilder, "chatClientBuilder must not be null");
        org.springframework.util.Assert.notNull(ragAdvisorFactory, "ragAdvisorFactory must not be null");
        this.chatClientBuilder = chatClientBuilder;
        this.ragAdvisorFactory = ragAdvisorFactory;
    }

    /**
     * Builds the streaming consultation for one request.
     *
     * @param request validated consultation request, must not be {@code null}
     * @return the streamed assistant text, never {@code null}
     * @throws BizException {@link ErrorCode#LLM_SERVICE_ERROR} when no chat model is configured
     * @throws IllegalArgumentException when the scope/retrieval combination is invalid (caller error)
     */
    public Flux<String> streamConsultation(ChatStreamRequest request) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new BizException(ErrorCode.LLM_SERVICE_ERROR,
                    "chat client not configured; enable spring.ai.model.chat and provide an API key");
        }
        ChatClient chatClient = builder.build();
        SessionCoordinate coordinate =
                SessionCoordinate.of(request.tenant(), request.dept(), request.session());
        MedDocumentScope scope = toScope(request);
        boolean includeShared =
                request.includeSharedDocuments() == null || request.includeSharedDocuments();

        MedRetrievalQuery.Builder queryBuilder =
                MedRetrievalQuery.builder(request.message(), scope).includeSharedDocuments(includeShared);
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
     * Resolves the RAG isolation scope from the request.
     *
     * @param request the consultation request, must not be {@code null}
     * @return a patient-scoped scope when {@code patientId} is present, otherwise a department-wide one
     */
    private MedDocumentScope toScope(ChatStreamRequest request) {
        if (request.patientId() != null && !request.patientId().isBlank()) {
            return MedDocumentScope.ofPatient(request.tenant(), request.dept(), request.patientId());
        }
        return MedDocumentScope.ofDepartment(request.tenant(), request.dept());
    }
}
