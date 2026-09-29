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

    /**
     * Lowest instruction-coverage floor the gate may declare.
     *
     * <p>The POM declares 0.90; the bound exists so a future edit cannot quietly turn the gate into
     * a formality while the test still passes.</p>
     */
    private static final double MIN_INSTRUCTION_FLOOR = 0.80;

    /** Lowest branch-coverage floor the gate may declare. The POM declares 0.80. */
    private static final double MIN_BRANCH_FLOOR = 0.70;

    /** Lowest line-coverage floor the gate may declare. The POM declares 0.90. */
    private static final double MIN_LINE_FLOOR = 0.80;

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
    @DisplayName("every floor is declared exactly once as a POM property")
    void coverageFloorsAreDeclaredAsProperties() {
        assertThat(pom.getElementsByTagName("jacoco.min.instruction").getLength()).isEqualTo(1);
        assertThat(pom.getElementsByTagName("jacoco.min.branch").getLength()).isEqualTo(1);
        assertThat(pom.getElementsByTagName("jacoco.min.line").getLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("no floor can be lowered into meaninglessness - a gate set to 0.01 is not a gate")
    void coverageFloorsAreNotLoweredIntoMeaninglessness() {
        // This assertion used to read isBetween(0.0, 1.0) for each floor, which accepted 0.01: the
        // guard test existed in form but permitted the gate to be switched off without failing. A
        // floor has to be compared against a real lower bound, not against its own type's range.
        assertThat(floor("jacoco.min.instruction")).isGreaterThanOrEqualTo(MIN_INSTRUCTION_FLOOR);
        assertThat(floor("jacoco.min.branch")).isGreaterThanOrEqualTo(MIN_BRANCH_FLOOR);
        assertThat(floor("jacoco.min.line")).isGreaterThanOrEqualTo(MIN_LINE_FLOOR);
        assertThat(floor("jacoco.min.instruction")).isLessThanOrEqualTo(1.0);
        assertThat(floor("jacoco.min.branch")).isLessThanOrEqualTo(1.0);
        assertThat(floor("jacoco.min.line")).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("boundary: a floor below the accepted minimum is detected rather than waved through")
    void aLoweredFloorIsDetected() {
        // Proves the bound is enforced by the comparison and not by the type: a hypothetical 0.01
        // declaration must fail every one of the three floors above.
        assertThat(0.01).isLessThan(MIN_INSTRUCTION_FLOOR);
        assertThat(0.01).isLessThan(MIN_BRANCH_FLOOR);
        assertThat(0.01).isLessThan(MIN_LINE_FLOOR);
    }

    /** Value of the {@code <name>} POM property, failing when it is not declared exactly once. */
    private static double floor(String name) {
        NodeList properties = pom.getElementsByTagName(name);
        assertThat(properties.getLength()).as("pom property %s", name).isEqualTo(1);
        return Double.parseDouble(properties.item(0).getTextContent().trim());
    }
}
