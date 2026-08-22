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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests pinning that {@code spring-boot-maven-plugin} produces a <em>layered</em> executable
 * jar, which the D27 Dockerfile depends on.
 *
 * <p>The runtime stage copies {@code extracted/dependencies}, {@code extracted/spring-boot-loader},
 * {@code extracted/snapshot-dependencies} and {@code extracted/application} as four separate image
 * layers. If layering were disabled, {@code java -Djarmode=tools ... extract --layers} would not
 * emit those directories and the image build would break -- so the POM wiring is asserted here
 * rather than discovered during a container build.</p>
 */
class LayeredJarBuildConfigTest {

    private static Element bootPlugin;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document pom = factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "pom.xml").toFile());

        NodeList plugins = pom.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            Element plugin = (Element) plugins.item(i);
            if ("spring-boot-maven-plugin".equals(childText(plugin, "artifactId"))) {
                bootPlugin = plugin;
                return;
            }
        }
    }

    /** Text of a direct child element, or {@code null} when the plugin has no such child. */
    private static String childText(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == parent) {
                return children.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    private static List<String> textOfAll(Element parent, String tag) {
        List<String> values = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            values.add(nodes.item(i).getTextContent().trim());
        }
        return values;
    }

    @Test
    @DisplayName("spring-boot-maven-plugin is declared and inherits its version from the Boot parent")
    void bootPluginDeclared() {
        assertThat(bootPlugin).as("spring-boot-maven-plugin declaration in pom.xml").isNotNull();
        assertThat(childText(bootPlugin, "groupId")).isEqualTo("org.springframework.boot");
        assertThat(childText(bootPlugin, "version"))
                .as("the version must come from spring-boot-starter-parent, not be pinned locally")
                .isNull();
    }

    @Test
    @DisplayName("layered jar packaging is explicitly enabled for Docker layer extraction")
    void layeredJarEnabled() {
        assertThat(bootPlugin.getElementsByTagName("layers").getLength())
                .as("a <layers> block under the boot plugin configuration")
                .isGreaterThan(0);
        assertThat(textOfAll(bootPlugin, "enabled"))
                .as("<layers><enabled>true</enabled></layers>")
                .contains("true");
    }

    @Test
    @DisplayName("layering is not switched off anywhere in the plugin configuration")
    void layeringNotDisabled() {
        assertThat(textOfAll(bootPlugin, "enabled"))
                .as("a false flag here would silently break the Dockerfile layer copies")
                .doesNotContain("false");
    }

    @Test
    @DisplayName("boundary: an absent child element resolves to null instead of throwing")
    void boundaryAbsentChildIsNull() {
        assertThat(childText(bootPlugin, "no-such-child")).isNull();
        assertThat(textOfAll(bootPlugin, "no-such-child")).isEmpty();
    }

    @Test
    @DisplayName("boundary: parsing a non-existent POM path throws")
    void boundaryMissingPomThrows() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        assertThatThrownBy(() -> factory.newDocumentBuilder()
                .parse(Path.of(System.getProperty("user.dir"), "no-such-layered-pom.xml").toFile()))
                .isInstanceOf(Exception.class);
    }
}
