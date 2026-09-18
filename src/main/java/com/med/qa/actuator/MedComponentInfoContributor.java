package com.med.qa.actuator;

import com.google.protobuf.GeneratedMessageV3;
import org.apache.ibatis.session.SqlSession;
import org.apache.shardingsphere.driver.ShardingSphereDriver;
import org.redisson.Redisson;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.SpringVersion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes the runtime technology matrix through the Actuator {@code /actuator/info} endpoint.
 *
 * <p>Operations and support need to know exactly which versions an instance runs without
 * cracking the jar open. Versions are read from the jar manifests and the official
 * {@code SpringVersion} / {@code SpringBootVersion} accessors, so the report can never drift from
 * what is actually on the classpath.</p>
 */
public class MedComponentInfoContributor implements InfoContributor {

    /** Info key holding the version matrix. */
    public static final String COMPONENTS_KEY = "components";

    /** Fallback used when a jar manifest carries no implementation version. */
    public static final String UNKNOWN_VERSION = "unknown";

    /** Component name to anchor class: the anchor's jar manifest carries the version. */
    private static final Map<String, Class<?>> COMPONENT_ANCHORS = new LinkedHashMap<>();

    static {
        COMPONENT_ANCHORS.put("spring-ai", EmbeddingModel.class);
        COMPONENT_ANCHORS.put("shardingsphere", ShardingSphereDriver.class);
        COMPONENT_ANCHORS.put("redisson", Redisson.class);
        COMPONENT_ANCHORS.put("mybatis", SqlSession.class);
        COMPONENT_ANCHORS.put("protobuf", GeneratedMessageV3.class);
    }

    /**
     * Adds the version matrix to the {@code info} payload.
     *
     * @param builder the Actuator info builder
     */
    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("java", resolveJavaVersion());
        components.put("spring-boot", SpringBootVersion.getVersion());
        components.put("spring-framework", SpringVersion.getVersion());
        COMPONENT_ANCHORS.forEach((name, anchor) -> components.put(name, resolvePackageVersion(anchor)));
        builder.withDetail(COMPONENTS_KEY, components);
    }

    /**
     * Lists the component names reported by this contributor, in report order.
     *
     * @return an immutable view of the reported component names
     */
    public static Map<String, Class<?>> reportedComponents() {
        return Map.copyOf(COMPONENT_ANCHORS);
    }

    /**
     * Resolves the running JVM version, falling back to {@link #UNKNOWN_VERSION}.
     *
     * @return the {@code java.version} system property, or {@code unknown} when unset
     */
    public static String resolveJavaVersion() {
        String version = System.getProperty("java.version");
        return version == null || version.isBlank() ? UNKNOWN_VERSION : version;
    }

    /**
     * Resolves the implementation version of the jar that provides {@code anchor}.
     *
     * @param anchor a class belonging to the component whose version is requested, not {@code null}
     * @return the jar manifest version, or {@link #UNKNOWN_VERSION} when the manifest is absent
     */
    public static String resolvePackageVersion(Class<?> anchor) {
        Package componentPackage = anchor.getPackage();
        String version = componentPackage == null ? null : componentPackage.getImplementationVersion();
        return version == null || version.isBlank() ? UNKNOWN_VERSION : version;
    }
}
