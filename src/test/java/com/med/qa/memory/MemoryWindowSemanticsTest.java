package com.med.qa.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the memory tier's window semantics (D46, the residual P2 item of the 2026-09-25
 * review).
 *
 * <p>Phases 8 and 9 kept finding the same class of defect: a comment promising behaviour the code did
 * not have. D44 fixed the behaviour — MySQL keeps the whole transcript, the window write is an
 * additive append, and the read path truncates a cold-cache replay — but left the wording in three
 * places either contradicting itself or silent about the one value that can unbound the prompt.
 * Since a comment cannot be covered by a line-coverage gate, the contract is pinned here by reading
 * the sources: the accurate statements must be present, and the retired ones must be gone for good.
 * The negative half is what makes this a guard rather than a spell-check — a future edit that
 * reintroduces "the read path returns the whole transcript" fails the build.</p>
 *
 * <p>Assertions run against a whitespace-collapsed copy of each file, because javadoc wraps a phrase
 * across lines at 100 columns and an assertion on the literal phrase would then fail for a reason
 * that has nothing to do with the contract (this happened on the first run of this class).</p>
 */
class MemoryWindowSemanticsTest {

    /** Source of the two-tier repository: owner of the bounded read and the full replay. */
    private static String repository;

    /** Source of the Redis cache: owner of the window bound and the trimming command. */
    private static String cache;

    /** Source of {@code med.cache.*}: owner of the cache window value. */
    private static String cacheProperties;

    /** Source of {@code med.chat.*}: owner of the prompt window value. */
    private static String chatProperties;

    /** Source of the Spring AI bridge: the read the model window actually performs. */
    private static String bridge;

    /** Full {@code application.yml}: the operator-facing wording of the same contract. */
    private static String applicationYml;

    @BeforeAll
    static void readSources() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        repository = flatten(root, "src/main/java/com/med/qa/memory/repository/MedChatMemoryRepository.java");
        cache = flatten(root, "src/main/java/com/med/qa/memory/cache/RedisMessageCache.java");
        cacheProperties = flatten(root, "src/main/java/com/med/qa/memory/cache/MedCacheProperties.java");
        chatProperties = flatten(root, "src/main/java/com/med/qa/config/MedChatMemoryProperties.java");
        bridge = flatten(root, "src/main/java/com/med/qa/memory/MedSpringAiChatMemoryRepository.java");
        applicationYml = flatten(root, "src/main/resources/application.yml");
    }

    /**
     * Reads a file and collapses its line structure, so a phrase can be asserted regardless of where
     * the 100-column wrap happened to fall.
     */
    private static String flatten(Path root, String relative) throws IOException {
        Path path = root.resolve(relative);
        assertThat(path).as("%s must exist", relative).exists();
        return Files.readString(path)
                .replaceAll("\\s*\\*+\\s*", " ")
                .replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("the repository states that its read path returns a bounded window")
    void readPathIsDocumentedAsBounded() {
        assertThat(repository).contains("bounded by {@code med.cache.max-messages}");
        assertThat(repository).contains("the tail of the durable transcript truncated to that window");
        // The retired claim: it said the read path replays the whole transcript, which the very next
        // paragraph contradicted.
        assertThat(repository).doesNotContain("the whole transcript replayed from MySQL");
    }

    @Test
    @DisplayName("the repository names reload as the only full-transcript read and keeps it bounded")
    void reloadIsTheOnlyFullReplay() {
        assertThat(repository).contains("the one read that does return the whole transcript");
        // Even the full replay must be documented as unable to widen the prompt path.
        assertThat(repository).contains("for verification and repair");
        assertThat(repository).contains("LTRIM");
    }

    @Test
    @DisplayName("the cache states the tier contract instead of only how it is implemented")
    void cacheStatesTheTierContract() {
        assertThat(cache).contains("Tier contract");
        assertThat(cache).contains("never the consultation");
        // The one value that makes the read path unbounded must be called out where the bound lives.
        assertThat(cache).contains("med.cache.max-messages: 0");
    }

    @Test
    @DisplayName("the cache documents that an over-long back-fill is trimmed, not rejected")
    void overLongBackFillIsDocumentedAsTrimmed() {
        assertThat(cache).contains("trimmed to its newest");
        assertThat(cache).contains("LTRIM");
    }

    @Test
    @DisplayName("med.cache.max-messages is documented as a cache bound, never a transcript bound")
    void cacheWindowBoundsTheCacheOnly() {
        assertThat(cacheProperties).contains("It is the size of the Redis window only");
        assertThat(cacheProperties).contains("med.chat.max-messages");
        // Setting 0 is the documented way to unbound the read path - an operational risk.
        assertThat(cacheProperties).contains("disables trimming");
    }

    @Test
    @DisplayName("med.chat.max-messages is documented as a prompt bound, never a transcript bound")
    void chatWindowBoundsThePromptOnly() {
        assertThat(chatProperties).contains("bounds the prompt, not the record");
        assertThat(chatProperties).contains("never trimmed to it");
        // The yml an operator reads must say the same thing as the class that binds it.
        assertThat(applicationYml).contains("It bounds the prompt only");
    }

    @Test
    @DisplayName("the Spring AI bridge documents that its read is the bounded window")
    void bridgeDocumentsTheBoundedRead() {
        assertThat(bridge).contains("not the whole transcript");
        assertThat(bridge).contains("med.cache.max-messages");
        assertThat(bridge).contains("MessageWindowChatMemory");
    }

    @Test
    @DisplayName("the memory-tier sources still agree with each other on the three tiers")
    void theThreeTiersAreNamedConsistently() {
        // One bound per tier, named in the place that owns it; a missing name means a future reader
        // has to guess which layer a value belongs to.
        List<String> boundNames = List.of(
                "med.cache.max-messages", "med.chat.max-messages", "med_message_");
        for (String bound : boundNames) {
            assertThat(repository).as("repository must name %s", bound).contains(bound);
        }
        assertThat(cache).contains("med.cache.max-messages");
        assertThat(chatProperties).contains("med.chat.max-messages");
    }
}
