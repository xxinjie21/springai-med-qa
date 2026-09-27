package com.med.qa.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Runs a {@link MedRetrievalBaseline} through the production retrieval path and reports what came
 * back.
 *
 * <h2>It measures, it does not retrieve</h2>
 * <p>Every case is executed by {@link MedRetrievalService} — the same service a consultation turn
 * uses — so the questions are embedded by the official {@code EmbeddingModel}, scored and ranked by
 * the official {@code RedisVectorStore}, and narrowed by the same
 * {@link MedRetrievalFilters} expressions. This class only turns a case into a query, collects the
 * identifiers that came back and hands them to
 * {@link MedRetrievalBaselineCaseResult#of(MedRetrievalBaselineCase, List)}. It computes no
 * similarity, applies no threshold of its own and re-orders nothing.</p>
 *
 * <h2>The retriever seam</h2>
 * <p>{@link #evaluate(MedRetrievalBaseline, Retriever)} takes the function that produces the
 * identifiers, which is what lets the evaluation policy be tested without Redis: a retriever that
 * returns nothing, or one that returns the whole corpus, must both make the baseline fail. The
 * production overload {@link #evaluate(MedRetrievalBaseline)} simply passes
 * {@link #retrieve(MedRetrievalBaselineCase)}.</p>
 *
 * <h2>Failures propagate</h2>
 * <p>A case whose retrieval throws — an embedding outage, a Redis error, a document returned outside
 * the requested scope — aborts the whole evaluation instead of being recorded as "zero recall". The
 * two are not the same thing: an outage is an outage, and reporting it as a quality regression would
 * send an operator looking for a filter bug that does not exist. {@link MedRetrievalService} already
 * fails closed for the isolation breach case.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public class MedRetrievalBaselineEvaluator {

    private final MedRetrievalService retrievalService;

    /**
     * Creates the evaluator.
     *
     * @param retrievalService production retrieval path, must not be {@code null}
     * @throws IllegalArgumentException if {@code retrievalService} is {@code null}
     */
    public MedRetrievalBaselineEvaluator(MedRetrievalService retrievalService) {
        if (retrievalService == null) {
            throw new IllegalArgumentException("retrievalService must not be null");
        }
        this.retrievalService = retrievalService;
    }

    /**
     * Evaluates a baseline against the production retrieval path.
     *
     * @param baseline golden set to evaluate, must not be {@code null}
     * @return the report, never {@code null}
     * @throws IllegalArgumentException if {@code baseline} is {@code null}
     */
    public MedRetrievalBaselineReport evaluate(MedRetrievalBaseline baseline) {
        return evaluate(baseline, this::retrieve);
    }

    /**
     * Evaluates a baseline against a caller-supplied retriever.
     *
     * @param baseline  golden set to evaluate, must not be {@code null}
     * @param retriever function producing the ranked identifiers of one case, must not be
     *                  {@code null}
     * @return the report, never {@code null}
     * @throws IllegalArgumentException if {@code baseline} or {@code retriever} is {@code null}
     */
    public MedRetrievalBaselineReport evaluate(MedRetrievalBaseline baseline, Retriever retriever) {
        if (baseline == null) {
            throw new IllegalArgumentException("baseline must not be null");
        }
        if (retriever == null) {
            throw new IllegalArgumentException("retriever must not be null");
        }
        List<MedRetrievalBaselineCaseResult> results = baseline.cases().stream()
                .map(baselineCase -> MedRetrievalBaselineCaseResult.of(baselineCase,
                        retriever.retrieve(baselineCase)))
                .toList();
        return new MedRetrievalBaselineReport(baseline.name(), baseline.version(), results);
    }

    /**
     * Evaluates a single case against the production retrieval path.
     *
     * @param baselineCase case to evaluate, must not be {@code null}
     * @return the measured outcome, never {@code null}
     * @throws IllegalArgumentException if {@code baselineCase} is {@code null}
     */
    public MedRetrievalBaselineCaseResult evaluateCase(MedRetrievalBaselineCase baselineCase) {
        if (baselineCase == null) {
            throw new IllegalArgumentException("baselineCase must not be null");
        }
        return MedRetrievalBaselineCaseResult.of(baselineCase, retrieve(baselineCase));
    }

    /**
     * Runs one case through the production retrieval path and returns the identifiers it ranked.
     *
     * @param baselineCase case to run, must not be {@code null}
     * @return the returned document identifiers, best first, never {@code null}
     * @throws IllegalArgumentException if {@code baselineCase} is {@code null}
     * @throws com.med.qa.common.exception.BizException as documented by
     *                                               {@link MedRetrievalService#search(MedRetrievalQuery)}
     */
    public List<String> retrieve(MedRetrievalBaselineCase baselineCase) {
        if (baselineCase == null) {
            throw new IllegalArgumentException("baselineCase must not be null");
        }
        return retrievalService.search(baselineCase.toRetrievalQuery()).stream()
                .map(Document::getId)
                .toList();
    }

    /**
     * Produces the ranked document identifiers of one baseline case.
     *
     * <p>An interface rather than a {@code Function} so the contract — "the identifiers the store
     * ranked, best first" — is stated once, in the place the evaluator reads it.</p>
     */
    @FunctionalInterface
    public interface Retriever {

        /**
         * Runs one case.
         *
         * @param baselineCase case to run, never {@code null}
         * @return the returned document identifiers, best first, never {@code null}
         */
        List<String> retrieve(MedRetrievalBaselineCase baselineCase);
    }
}
