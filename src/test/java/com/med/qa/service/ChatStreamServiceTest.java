package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.controller.dto.ChatStreamRequest;
import com.med.qa.rag.MedDocumentScope;
import com.med.qa.rag.MedRagAdvisorFactory;
import com.med.qa.rag.MedRetrievalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests of the streaming consultation orchestration.
 *
 * <p>The official {@link ChatClient} chain and the {@link MedRagAdvisorFactory} are mocked, so the
 * test exercises only the wiring of the consultation prompt: conversation id, RAG advisor scope, and
 * the returned content stream. No model, embedding endpoint or vector store is contacted.</p>
 */
class ChatStreamServiceTest {

    private ObjectProvider<ChatClient.Builder> builderProvider;

    private ChatClient.Builder builder;

    private ChatClient chatClient;

    private ChatClient.ChatClientRequestSpec requestSpec;

    private ChatClient.StreamResponseSpec streamSpec;

    private MedRagAdvisorFactory ragAdvisorFactory;

    private Advisor ragAdvisor;

    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        builderProvider = mock(ObjectProvider.class);
        builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ragAdvisorFactory = mock(MedRagAdvisorFactory.class);
        ragAdvisor = mock(Advisor.class);
        service = new ChatStreamService(builderProvider, ragAdvisorFactory);
    }

    private void stubHappyChain() {
        when(builderProvider.getIfAvailable()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("Hello", " world"));
        when(ragAdvisorFactory.createAdvisor(any(MedRetrievalQuery.class))).thenReturn(ragAdvisor);
    }

    @Nested
    @DisplayName("streaming assembly")
    class Streaming {

        @Test
        @DisplayName("returns the streamed content and wires the RAG advisor")
        void streamsAndAddsRagAdvisor() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-1", "card", "s-1", "P-1", "what dose", true, null);

            Flux<String> flux = service.streamConsultation(request);

            assertThat(flux.collectList().block()).containsExactly("Hello", " world");
            verify(chatClient).prompt();
            verify(streamSpec).content();
            verify(ragAdvisorFactory).createAdvisor(any(MedRetrievalQuery.class));
            verify(requestSpec).advisors(ragAdvisor);
        }

        @Test
        @DisplayName("a patient-scoped request retrieves the patient's own and shared documents")
        void patientScope() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-1", "card", "s-1", "P-1", "what dose", null, null);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            service.streamConsultation(request);

            verify(ragAdvisorFactory).createAdvisor(captor.capture());
            assertThat(captor.getValue().getScope().isPatientScoped()).isTrue();
            assertThat(captor.getValue().isIncludeSharedDocuments()).isTrue();
        }

        @Test
        @DisplayName("a request without a patient id falls back to department-wide shared documents")
        void departmentScope() {
            stubHappyChain();
            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-1", "card", "s-1", null, "guideline", null, null);
            ArgumentCaptor<MedRetrievalQuery> captor = ArgumentCaptor.forClass(MedRetrievalQuery.class);

            service.streamConsultation(request);

            verify(ragAdvisorFactory).createAdvisor(captor.capture());
            assertThat(captor.getValue().getScope().isPatientScoped()).isFalse();
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("rejects the turn when no chat client is configured")
        void noChatClient() {
            when(builderProvider.getIfAvailable()).thenReturn(null);
            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-1", "card", "s-1", null, "q", null, null);

            assertThatThrownBy(() -> service.streamConsultation(request))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.LLM_SERVICE_ERROR);
        }

        @Test
        @DisplayName("forwards an invalid scope combination as a caller error")
        void invalidScope() {
            when(builderProvider.getIfAvailable()).thenReturn(builder);
            when(builder.build()).thenReturn(chatClient);
            when(chatClient.prompt()).thenReturn(requestSpec);
            when(requestSpec.user(anyString())).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
            when(requestSpec.advisors(any(Advisor.class))).thenReturn(requestSpec);
            when(requestSpec.stream()).thenReturn(streamSpec);
            when(streamSpec.content()).thenReturn(Flux.just("x"));
            when(ragAdvisorFactory.createAdvisor(any(MedRetrievalQuery.class)))
                    .thenThrow(new IllegalArgumentException("department scope cannot exclude shared"));

            ChatStreamRequest request =
                    new ChatStreamRequest("hosp-1", "card", "s-1", null, "q", false, null);

            assertThatThrownBy(() -> service.streamConsultation(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
