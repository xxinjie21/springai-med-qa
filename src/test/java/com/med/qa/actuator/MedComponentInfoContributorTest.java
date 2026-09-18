package com.med.qa.actuator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MedComponentInfoContributor}.
 *
 * <p>The contributor is pure metadata: no store, no network, so it can be driven directly. The
 * assertions focus on the published contract (one {@code components} map, every documented entry
 * present and non-blank) plus the {@code unknown} fallback used when a jar ships no manifest.</p>
 */
class MedComponentInfoContributorTest {

    @Test
    @DisplayName("contribute publishes a components map holding every documented entry")
    void publishesTheFullVersionMatrix() {
        Info.Builder builder = new Info.Builder();

        new MedComponentInfoContributor().contribute(builder);

        Map<String, Object> details = builder.build().getDetails();
        assertThat(details).containsKey(MedComponentInfoContributor.COMPONENTS_KEY);
        @SuppressWarnings("unchecked")
        Map<String, Object> components =
                (Map<String, Object>) details.get(MedComponentInfoContributor.COMPONENTS_KEY);
        assertThat(components).containsKeys("java", "spring-boot", "spring-framework");
        MedComponentInfoContributor.reportedComponents().keySet()
                .forEach(name -> assertThat(components).containsKey(name));
        components.values().forEach(value -> assertThat(String.valueOf(value)).isNotBlank());
    }

    @Test
    @DisplayName("the reported java version is the one actually running the JVM")
    void reportsTheRunningJavaVersion() {
        Info.Builder builder = new Info.Builder();

        new MedComponentInfoContributor().contribute(builder);

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) builder.build()
                .getDetails().get(MedComponentInfoContributor.COMPONENTS_KEY);
        assertThat(components.get("java")).isEqualTo(System.getProperty("java.version"));
    }

    @Test
    @DisplayName("reportedComponents exposes the documented component set")
    void reportsTheDocumentedComponentSet() {
        assertThat(MedComponentInfoContributor.reportedComponents())
                .containsKeys("spring-ai", "shardingsphere", "redisson", "mybatis", "protobuf");
    }

    @Test
    @DisplayName("reportedComponents is a defensive copy, callers cannot mutate the matrix")
    void reportedComponentsIsImmutable() {
        Map<String, Class<?>> components = MedComponentInfoContributor.reportedComponents();

        assertThatThrownBy(() -> components.put("hacked", String.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("resolveJavaVersion falls back to unknown when the property is blank")
    void javaVersionFallsBackWhenPropertyBlank() {
        String original = System.getProperty("java.version");
        try {
            System.setProperty("java.version", "   ");
            assertThat(MedComponentInfoContributor.resolveJavaVersion())
                    .isEqualTo(MedComponentInfoContributor.UNKNOWN_VERSION);
        } finally {
            if (original == null) {
                System.clearProperty("java.version");
            } else {
                System.setProperty("java.version", original);
            }
        }
    }

    @Test
    @DisplayName("resolveJavaVersion reports the running JVM version")
    void javaVersionReadsTheSystemProperty() {
        assertThat(MedComponentInfoContributor.resolveJavaVersion())
                .isEqualTo(System.getProperty("java.version"));
    }

    @Test
    @DisplayName("resolvePackageVersion falls back to unknown when the jar has no manifest version")
    void packageVersionFallsBackForPlatformClasses() {
        assertThat(MedComponentInfoContributor.resolvePackageVersion(String.class))
                .isEqualTo(MedComponentInfoContributor.UNKNOWN_VERSION);
    }

    @Test
    @DisplayName("resolvePackageVersion reads the manifest of a third party jar")
    void packageVersionReadsThirdPartyManifest() {
        assertThat(MedComponentInfoContributor.resolvePackageVersion(EmbeddingModel.class)).isNotBlank();
    }
}
