package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the JaCoCo coverage gate introduced in D32.
 *
 * <p>Reporting coverage is documentation; failing the build on a regression is a quality gate. The
 * gate is enforced by the {@code jacoco-coverage-gate} execution bound to the {@code verify} phase
 * that {@code .github/workflows/ci.yml} runs, with the floors declared as POM properties. These
 * tests parse the POM with the JDK DOM parser so that dropping the gate, unbinding it from the
 * phase CI actually runs, or silently lowering a floor fails fast.</p>
 */
class CoverageGateConfigTest {

    private static Element jacocoPlugin;
    private static Element coverageGate;
    private static Document pom;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        pom = factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "pom.xml").toFile());

        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if ("jacoco-maven-plugin".equals(childText(plugin, "artifactId"))) {
                jacocoPlugin = plugin;
                break;
            }
        }
        assertThat(jacocoPlugin).as("jacoco-maven-plugin declaration").isNotNull();
        coverageGate = executionById(jacocoPlugin, "jacoco-coverage-gate");
    }

    private static String childText(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == parent) {
                return children.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    /** Reads the first {@code tag} descendant, used for nodes nested inside {@code configuration}. */
    private static String descendantText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static Element executionById(Element plugin, String id) {
        NodeList executions = plugin.getElementsByTagName("execution");
        for (int i = 0; i < executions.getLength(); i++) {
            Element execution = (Element) executions.item(i);
            if (id.equals(childText(execution, "id"))) {
                return execution;
            }
        }
        return null;
    }

    /** Collects every {@code <limit>} of the gate as counter/minimum pairs. */
    private static List<String> limits() {
        List<String> found = new ArrayList<>();
        NodeList limitNodes = coverageGate.getElementsByTagName("limit");
        for (int i = 0; i < limitNodes.getLength(); i++) {
            Element limit = (Element) limitNodes.item(i);
            found.add(childText(limit, "counter") + "=" + childText(limit, "minimum"));
        }
        return found;
    }

    @Test
    @DisplayName("the coverage gate execution exists and runs the check goal")
    void coverageGateExecutionIsDeclared() {
        assertThat(coverageGate).as("jacoco-coverage-gate execution").isNotNull();
        assertThat(descendantText(coverageGate, "goal")).isEqualTo("check");
    }

    @Test
    @DisplayName("the gate is bound to verify, the phase the CI workflow invokes")
    void coverageGateIsBoundToVerify() {
        assertThat(childText(coverageGate, "phase")).isEqualTo("verify");
    }

    @Test
    @DisplayName("the gate halts the build instead of only warning")
    void coverageGateHaltsOnFailure() {
        assertThat(descendantText(coverageGate, "haltOnFailure")).isEqualTo("true");
    }

    @Test
    @DisplayName("the gate asserts instruction, branch and line ratios as covered ratios")
    void coverageGateCoversAllThreeCounters() {
        assertThat(limits()).containsExactlyInAnyOrder(
                "INSTRUCTION=${jacoco.min.instruction}",
                "BRANCH=${jacoco.min.branch}",
                "LINE=${jacoco.min.line}");
        NodeList values = coverageGate.getElementsByTagName("value");
        for (int i = 0; i < values.getLength(); i++) {
            assertThat(values.item(i).getTextContent().trim()).isEqualTo("COVEREDRATIO");
        }
    }

    @Test
    @DisplayName("the gate applies to the whole bundle, not to a single package")
    void coverageGateAppliesToTheWholeBundle() {
        assertThat(descendantText(coverageGate, "element")).isEqualTo("BUNDLE");
    }

    @Test
    @DisplayName("every floor is declared as a POM property with a sane value")
    void coverageFloorsAreDeclaredAsProperties() {
        NodeList properties = pom.getElementsByTagName("jacoco.min.instruction");
        assertThat(properties.getLength()).isEqualTo(1);
        assertThat(Double.parseDouble(properties.item(0).getTextContent().trim()))
                .isBetween(0.0, 1.0);
        assertThat(pom.getElementsByTagName("jacoco.min.branch").getLength()).isEqualTo(1);
        assertThat(Double.parseDouble(
                pom.getElementsByTagName("jacoco.min.branch").item(0).getTextContent().trim()))
                .isBetween(0.0, 1.0);
        assertThat(pom.getElementsByTagName("jacoco.min.line").getLength()).isEqualTo(1);
        assertThat(Double.parseDouble(
                pom.getElementsByTagName("jacoco.min.line").item(0).getTextContent().trim()))
                .isBetween(0.0, 1.0);
    }
}
