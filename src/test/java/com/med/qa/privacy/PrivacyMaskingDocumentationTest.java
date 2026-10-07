package com.med.qa.privacy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the privacy layer's own documentation (D46, P2-1 of the 2026-09-25 review).
 *
 * <p>{@link MaskType}, the package javadoc and {@code @Desensitize} all claimed that no mask pattern
 * is hand-written in this project — while {@link MaskType#mask} routes the medical record number to
 * the enum's own keep-edges mask, because Hutool ships no strategy for it. A self-negating comment is
 * not a harmless wording problem in a privacy layer: it tells the next reader that the masking rules
 * are maintained elsewhere, so nobody reviews them here. The exception is now stated explicitly, and
 * these tests keep the absolute wording from coming back.</p>
 *
 * <p>Assertions run against a whitespace-collapsed copy of each file: javadoc wraps a phrase across
 * lines at 100 columns, so asserting the literal phrase would fail for a reason unrelated to the
 * contract (which is exactly what happened on this class's first run).</p>
 */
class PrivacyMaskingDocumentationTest {

    /** The enum that actually masks; its javadoc is the contract. */
    private static String maskType;

    /** The package javadoc, which is the first thing a reader of this package sees. */
    private static String packageInfo;

    /** The annotation javadoc, which is what a caller reads before using the mask. */
    private static String desensitizeAnnotation;

    @BeforeAll
    static void readSources() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        maskType = flatten(root, "src/main/java/com/med/qa/privacy/MaskType.java");
        packageInfo = flatten(root, "src/main/java/com/med/qa/privacy/package-info.java");
        desensitizeAnnotation =
                flatten(root, "src/main/java/com/med/qa/privacy/annotation/Desensitize.java");
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
    @DisplayName("no privacy source claims that nothing here is hand-written")
    void noAbsoluteClaimSurvives() {
        Map<String, String> sources = Map.of(
                "MaskType.java", maskType,
                "privacy/package-info.java", packageInfo,
                "annotation/Desensitize.java", desensitizeAnnotation);
        List<String> retiredClaims = List.of(
                "No mask pattern is hand-written",
                "no mask pattern is computed",
                "no hand-written masking logic");
        sources.forEach((name, source) -> {
            for (String claim : retiredClaims) {
                assertThat(source)
                        .as("%s must not repeat the retired claim \"%s\"", name, claim)
                        .doesNotContain(claim);
            }
        });
    }

    @Test
    @DisplayName("every privacy source names Hutool as the delegation target")
    void hutoolIsNamedAsTheDelegate() {
        assertThat(maskType).contains("Hutool");
        assertThat(packageInfo).contains("Hutool");
        assertThat(desensitizeAnnotation).contains("Hutool");
        // And the delegation must be described per strategy, not as a blanket claim.
        assertThat(maskType).contains("DesensitizedUtil");
    }

    @Test
    @DisplayName("the medical-record exception is stated where the mask is defined and where it is used")
    void theMedicalRecordExceptionIsDocumented() {
        // MaskType: the strategy itself, named so a reader can find the code.
        assertThat(maskType).contains("maskKeepEdges");
        assertThat(maskType).contains("no dedicated Hutool strategy");
        // The other two entry points must admit the same exception.
        assertThat(packageInfo).contains("medical record");
        assertThat(packageInfo).contains("keep-edges");
        assertThat(desensitizeAnnotation).contains("medical record");
        assertThat(desensitizeAnnotation).contains("keep-edges");
    }

    @Test
    @DisplayName("the corrected wording is marked as a correction, so the history is not re-litigated")
    void theCorrectionIsAttributedToD46() {
        assertThat(maskType).contains("D46");
        assertThat(packageInfo).contains("D46");
        assertThat(desensitizeAnnotation).contains("D46");
    }
}
