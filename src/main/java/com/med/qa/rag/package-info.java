/**
 * RAG layer: assembles Spring AI official components (RedisVectorStore,
 * QuestionAnswerAdvisor, FilterExpressionBuilder). Retrieval is pure vector similarity
 * search with dept/patient metadata tag filtering only - no text content parsing.
 */
package com.med.qa.rag;
