package com.med.qa.rag;

import com.med.qa.config.VectorStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-level guard tests of the RAG advisor factory wiring.
 *
 * <p>The factory is an eager singleton that injects the vector store {@link org.springframework.context.annotation.Lazy},
 * so it must assemble advisors offline — without a Redis Stack connection. These tests pin that
 * wiring the factory does not drag the store into the startup path, and that a scoped advisor can be
 * built from a real Spring context (the isolation filter expression is the same one the retrieval
 * service uses).</p>
 */
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MedRagAdvisorContextTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private MedRagAdvisorFactory advisorFactory;

    @Test
    @DisplayName("the advisor factory is available to the application")
    void factoryIsWired() {
        assertThat(advisorFactory).isNotNull();
        assertThat(context.getBeanNamesForType(MedRagAdvisorFactory.class)).hasSize(1);
    }

    @Test
    @DisplayName("a scoped QuestionAnswerAdvisor can be built offline")
    void advisorBuildsOffline() {
        MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");

        Advisor advisor = advisorFactory.createAdvisor(scope);

        assertThat(advisor).isInstanceOf(QuestionAnswerAdvisor.class);
        assertThat(advisor.getOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("wiring the factory does not instantiate the vector store")
    void vectorStoreStaysUninstantiated() {
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE)).isFalse();
        assertThat(context.getBeanFactory().containsSingleton(VectorStoreConfig.VECTOR_STORE_JEDIS)).isFalse();
    }

    @Test
    @DisplayName("the assembled search request carries the same isolation filter as the retrieval service")
    void searchRequestCarriesScope() {
        MedDocumentScope scope = MedDocumentScope.ofPatient("tenant-1", "dept-cardio", "patient-9");

        SearchRequest request = advisorFactory.toSearchRequest(scope, true);

        assertThat(request.getFilterExpression()).isEqualTo(MedRetrievalFilters.scope(scope, true));
        assertThat(request.getTopK()).isEqualTo(4);
    }
}
