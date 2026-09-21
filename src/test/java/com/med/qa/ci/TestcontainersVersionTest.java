package com.med.qa.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Guard test for the Testcontainers version pinned in {@code pom.xml}.
 *
 * <p>The D30 integration suite is gated by {@code DockerAvailableCondition}, which asks
 * Testcontainers whether a Docker daemon is reachable. Up to Testcontainers 1.20.6 that probe
 * returns {@code false} on a Docker Engine that no longer accepts the API version the library
 * falls back to, so the entire integration suite is <em>silently skipped</em> instead of run - it
 * looks exactly like "no Docker on this machine". That is how two production-blocking defects
 * (a missing Flyway MySQL module and migrations driven through the ShardingSphere proxy) survived
 * every build until D34 finally ran the suite for real.</p>
 *
 * <p>Because the failure mode is silent, it needs an explicit guard: this test fails the build if
 * the pinned version drops below 1.21.0, or if the BOM import that keeps the three modules in step
 * is removed.</p>
 */
class TestcontainersVersionTest {

    /**
     * Lowest Testcontainers release whose Docker API fallback clears the floor enforced by current
     * Docker Engine versions. Anything below this makes the integration suite skip itself.
     */
    private static final List<Integer> MINIMUM_SUPPORTED_VERSION = List.of(1, 21, 0);

    private static Document pom;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        pom = factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "pom.xml").toFile());
    }

    @Test
    @DisplayName("the pinned Testcontainers version is at or above the Docker-API-safe floor")
    void pinnedVersionIsNewEnough() {
        String version = childText(root(), "properties", "testcontainers.version");

        assertThat(version).as("pom.xml must pin testcontainers.version explicitly").isNotBlank();
        assertThat(compare(parseVersion(version), MINIMUM_SUPPORTED_VERSION))
                .as("testcontainers %s must not be lower than 1.21.0, or the integration suite "
                        + "silently skips itself on a current Docker Engine", version)
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("the Testcontainers BOM is imported so the three modules cannot drift apart")
    void bomIsImportedFromThePinnedVersion() {
        Element managedDependencies = directChild(directChild(root(), "dependencyManagement"), "dependencies");
        Element bom = elements(managedDependencies, "dependency").stream()
                .filter(dependency -> "testcontainers-bom".equals(childText(dependency, "artifactId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "pom.xml must import org.testcontainers:testcontainers-bom"));

        assertThat(childText(bom, "groupId")).isEqualTo("org.testcontainers");
        assertThat(childText(bom, "type")).isEqualTo("pom");
        assertThat(childText(bom, "scope")).isEqualTo("import");
        assertThat(childText(bom, "version"))
                .as("the BOM must follow the property, never a literal version")
                .isEqualTo("${testcontainers.version}");
    }

    @Test
    @DisplayName("every Testcontainers module the suite uses is a test-scoped dependency")
    void modulesAreTestScoped() {
        List<Element> dependencies = elements(directChild(root(), "dependencies"), "dependency");
        for (String artifact : List.of("testcontainers", "junit-jupiter", "mysql")) {
            Element dependency = dependencies.stream()
                    .filter(candidate -> "org.testcontainers".equals(childText(candidate, "groupId")))
                    .filter(candidate -> artifact.equals(childText(candidate, "artifactId")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "pom.xml must depend on org.testcontainers:" + artifact));
            assertThat(childText(dependency, "scope"))
                    .as("%s must stay test-scoped so it never reaches the runtime image", artifact)
                    .isEqualTo("test");
            assertThat(childText(dependency, "version"))
                    .as("%s must be version-managed by the imported BOM", artifact)
                    .isNull();
        }
    }

    private static Element root() {
        return pom.getDocumentElement();
    }

    private static Element directChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String tag) {
        Element child = directChild(parent, tag);
        return (child == null) ? null : child.getTextContent().trim();
    }

    private static String childText(Element parent, String wrapper, String tag) {
        return childText(directChild(parent, wrapper), tag);
    }

    private static List<Element> elements(Element parent, String tag) {
        List<Element> matches = new ArrayList<>();
        if (parent == null) {
            return matches;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && tag.equals(element.getTagName())) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static List<Integer> parseVersion(String version) {
        String[] segments = version.trim().split("\\.");
        List<Integer> parsed = new ArrayList<>();
        for (String segment : segments) {
            // Ignore any qualifier suffix (for example 1.21.4-M1) instead of failing the guard.
            String digits = segment.replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) {
                break;
            }
            parsed.add(Integer.parseInt(digits));
        }
        return parsed;
    }

    private static int compare(List<Integer> left, List<Integer> right) {
        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            int a = (i < left.size()) ? left.get(i) : 0;
            int b = (i < right.size()) ? right.get(i) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }
}
