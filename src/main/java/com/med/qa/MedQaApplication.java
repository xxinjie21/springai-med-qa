package com.med.qa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point for the hospital-grade AI consultation backend.
 *
 * <p>Built on Spring Boot 3 and (in later iterations) Spring AI, this service provides
 * streaming LLM consultation, RAG retrieval over a vector store, sharded conversation
 * memory storage and medical-grade security/audit/privacy capabilities.</p>
 */
@SpringBootApplication
public class MedQaApplication {

    /**
     * Boots the Spring application context.
     *
     * @param args standard command-line arguments passed to the Spring runtime
     */
    public static void main(String[] args) {
        SpringApplication.run(MedQaApplication.class, args);
    }
}
