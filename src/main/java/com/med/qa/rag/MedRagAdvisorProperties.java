package com.med.qa.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning of the RAG question-answer advisor assembled by {@link MedRagAdvisorFactory}.
 *
 * <p>Bound from {@code med.rag.advisor.*}. These are presentation and ordering knobs of the official
 * {@link org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor}, not an
 * algorithm: the retrieval, the tenant/dept/patient tag filtering and the similarity scoring are all
 * delegated to the official vector store and to {@link MedRetrievalFilters}. What is configured here
 * is only where the advisor sits in the advisor chain and, optionally, the wording of the
 * augmentation prompt.</p>
 */
@ConfigurationProperties(prefix = MedRagAdvisorProperties.PREFIX)
public class MedRagAdvisorProperties {

    /** Configuration prefix bound by Spring Boot. */
    public static final String PREFIX = "med.rag.advisor";

    /**
     * Default augmentation prompt. It uses the two placeholders the official advisor fills at runtime:
     * {@code {query}} (the patient's question) and {@code {question_answer_context}} (the retrieved
     * guideline / record snippets). The model is told to answer only from the context so a missing
     * guideline surfaces as an honest "I can't answer" rather than a hallucination.
     */
    public static final String DEFAULT_PROMPT_TEMPLATE = """
            你是一名严谨的医院问诊助理。请仅依据下方【参考资料】中的内容回答用户问题，不要凭空编造；
            如果参考资料中没有相关信息，请明确告知无法回答。

            【参考资料】
            ---------------------
            {question_answer_context}
            ---------------------

            用户问题：{query}
            """;

    private int order = 1;

    private String promptTemplate = DEFAULT_PROMPT_TEMPLATE;

    /**
     * Returns the advisor execution order.
     *
     * <p>The memory advisor (D12) runs at order 0, so the RAG advisor defaulting to 1 retrieves
     * evidence after the remembered transcript has been loaded into the request.</p>
     *
     * @return advisor order, always {@code >= 0}
     */
    public int getOrder() {
        return order;
    }

    /**
     * Sets the advisor execution order.
     *
     * @param order advisor order, must not be negative
     * @throws IllegalArgumentException if the order is negative
     */
    public void setOrder(int order) {
        if (order < 0) {
            throw new IllegalArgumentException(
                    PREFIX + ".order must not be negative but is " + order);
        }
        this.order = order;
    }

    /**
     * Returns the augmentation prompt template.
     *
     * @return the prompt template, never {@code null} or blank
     */
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * Sets the augmentation prompt template.
     *
     * <p>Must contain the {@code {query}} and {@code {question_answer_context}} placeholders the
     * official advisor fills, otherwise retrieved evidence is silently dropped from the prompt.</p>
     *
     * @param promptTemplate augmentation prompt template, must not be {@code null} or blank
     * @throws IllegalArgumentException if the template is {@code null} or blank
     */
    public void setPromptTemplate(String promptTemplate) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            throw new IllegalArgumentException(PREFIX + ".prompt-template must not be blank");
        }
        this.promptTemplate = promptTemplate;
    }
}
