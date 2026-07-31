package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for the JaCoCo coverage configuration in {@code pom.xml}.
 *
 * <p>The D5 CI pipeline relies on the jacoco-maven-plugin producing
 * {@code target/site/jacoco} during {@code mvn verify}. These tests parse
 * the POM with the JDK DOM parser and assert the plugin wiring
 * (prepare-agent + report@verify) plus the exclusion of generated
 * protobuf classes, so a broken coverage setup fails fast.</p>
 */
class JacocoBuildConfigTest {

    private static Element jacocoPlugin;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document pom = factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "pom.xml").toFile());

        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if ("jacoco-maven-plugin".equals(childText(plugin, "artifactId"))) {
                jacocoPlugin = plugin;
                return;
            }
        }
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

    private static List<Element> executions() {
        List<Element> result = new ArrayList<>();
        NodeList executions = jacocoPlugin.getElementsByTagName("execution");
        for (int i = 0; i < executions.getLength(); i++) {
            result.add((Element) executions.item(i));
        }
        return result;
    }

    private static Optional<Element> executionWithGoal(String goal) {
        return executions().stream()
                .filter(e -> e.getElementsByTagName("goal").getLength() > 0
                        && goal.equals(e.getElementsByTagName("goal").item(0).getTextContent().trim()))
                .findFirst();
    }

    @Test
    @DisplayName("jacoco-maven-plugin is declared with an explicit GA version property")
    void jacocoPluginDeclared() {
        assertThat(jacocoPlugin).as("jacoco-maven-plugin declaration in pom.xml").isNotNull();
        assertThat(childText(jacocoPlugin, "groupId")).isEqualTo("org.jacoco");
        assertThat(childText(jacocoPlugin, "version")).isEqualTo("${jacoco-maven-plugin.version}");
    }

    @Test
    @DisplayName("prepare-agent execution exists so surefire runs with the coverage agent")
    void prepareAgentGoalBound() {
        assertThat(executionWithGoal("prepare-agent")).isPresent();
    }

    @Test
    @DisplayName("report goal is bound to the verify phase for CI artifact upload")
    void reportGoalBoundToVerify() {
        Element report = executionWithGoal("report").orElseThrow();
        assertThat(childText(report, "phase")).isEqualTo("verify");
    }

    @Test
    @DisplayName("generated protobuf classes are excluded from coverage metrics")
    void protoClassesExcluded() {
        NodeList excludes = jacocoPlugin.getElementsByTagName("exclude");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < excludes.getLength(); i++) {
            values.add(excludes.item(i).getTextContent().trim());
        }
        assertThat(values).contains("com/med/qa/proto/**");
    }

    @Test
    @DisplayName("boundary: parsing a non-existent pom path throws")
    void missingPomThrows() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        assertThatThrownBy(() -> factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "no-such-pom.xml").toFile()))
                .isInstanceOf(Exception.class);
        assertThat(Files.exists(Path.of(System.getProperty("user.dir"), "no-such-pom.xml"))).isFalse();
    }
}
