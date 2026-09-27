package com.med.qa.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A versioned golden set of {@link MedRetrievalBaselineCase retrieval cases}: the frozen definition
 * of what a correct medical RAG retrieval looks like for a fixed corpus.
 *
 * <h2>Why it is data rather than code</h2>
 * <p>Growing the baseline has to be a reviewable edit, not a code change: adding a question, a
 * forbidden document or a recall requirement is how the retrieval layer's contract is tightened
 * over time, and it should show up in a diff as data. The set is therefore stored as JSON and read
 * by {@link MedRetrievalBaselineLoader}.</p>
 *
 * <h2>What a baseline does not do</h2>
 * <p>It never contacts a vector store, never embeds anything and never scores a document. It is
 * evaluated by {@link MedRetrievalBaselineEvaluator}, which compares what the retrieval returned
 * with what the cases declared. Keeping the two apart means a change to the evaluation policy cannot
 * silently rewrite the expectations, and vice versa.</p>
 *
 * <h2>Versioning</h2>
 * <p>{@link #version()} is an opaque label carried into every report. Bumping it is the convention
 * for a change that makes previously correct retrievals fail — for instance adding a case, or
 * tightening {@code minRecall} — so a CI failure can be told apart from a baseline that simply moved
 * on.</p>
 *
 * <p>Instances are immutable and safe to share.</p>
 */
public record MedRetrievalBaseline(String name, String version, List<MedRetrievalBaselineCase> cases) {

    /**
     * Validates and normalises a baseline.
     *
     * @throws IllegalArgumentException if the name or version is blank, the case list is
     *                                  {@code null}, empty or holds a {@code null} entry, or two
     *                                  cases share a name — a duplicated name would make a report
     *                                  ambiguous and silently hide one of the two
     */
    public MedRetrievalBaseline {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("baseline name must not be blank");
        }
        if (!StringUtils.hasText(version)) {
            throw new IllegalArgumentException("baseline version must not be blank");
        }
        if (cases == null) {
            throw new IllegalArgumentException("baseline cases must not be null");
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("baseline must declare at least one case");
        }
        List<MedRetrievalBaselineCase> copy = new ArrayList<>(cases.size());
        Set<String> seen = new LinkedHashSet<>();
        for (MedRetrievalBaselineCase baselineCase : cases) {
            if (baselineCase == null) {
                throw new IllegalArgumentException("baseline cases must not contain null entries");
            }
            if (!seen.add(baselineCase.name())) {
                throw new IllegalArgumentException("baseline contains duplicate case name: "
                        + baselineCase.name());
            }
            copy.add(baselineCase);
        }
        cases = List.copyOf(copy);
    }

    /**
     * Returns the number of cases in the set.
     *
     * @return the size of {@link #cases()}, always {@code >= 1}
     */
    public int caseCount() {
        return cases.size();
    }

    /**
     * Returns the case names, in declaration order.
     *
     * @return a fresh immutable list of case names, never {@code null} nor empty
     */
    public List<String> caseNames() {
        return cases.stream().map(MedRetrievalBaselineCase::name).toList();
    }

    /**
     * Looks a case up by name.
     *
     * @param caseName name to look for, may be {@code null}
     * @return the matching case, or an empty optional when the set holds no such name
     */
    public Optional<MedRetrievalBaselineCase> findCase(String caseName) {
        return cases.stream().filter(entry -> entry.name().equals(caseName)).findFirst();
    }

    /**
     * Renders the baseline without any question text.
     *
     * @return a description naming the set, its version and its case names, never {@code null}
     */
    @Override
    public String toString() {
        return "MedRetrievalBaseline{name='" + name + '\''
                + ", version='" + version + '\''
                + ", cases=" + caseNames() + '}';
    }
}
