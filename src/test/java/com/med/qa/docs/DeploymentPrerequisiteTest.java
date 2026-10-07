package com.med.qa.docs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the two deployment prerequisites corrected in D46 (P2-3 and P2-4).
 *
 * <p>Both defects share a shape: the handbook said nothing, and the consequence was silent.
 * A MySQL whose default charset is not {@code utf8mb4} stores Chinese clinical text as mojibake
 * without raising a single error (the V1 DDL deliberately omits {@code DEFAULT CHARSET} so the same
 * script also runs against H2), and an H2 web console that a later change quietly switches on would
 * expose an unauthenticated in-process SQL console on the production classpath. Neither is
 * observable from a health probe, so both are pinned here instead of being trusted to memory.</p>
 *
 * <p>The assertions deliberately cross files: the handbook's claim is checked against the owners of
 * that claim (compose, the init script, the migration, {@code pom.xml}, and every main configuration
 * file). A handbook that documents a prerequisite nobody implements is as wrong as an implementation
 * nobody documents.</p>
 *
 * <p>The H2-console check loads every {@code application*} configuration through Spring Boot's own
 * {@link YamlPropertySourceLoader} instead of grepping the text. A text search for
 * {@code h2.console} misses the perfectly valid nested form — <code>spring: / h2: / console:</code> —
 * which is exactly how the console would realistically be switched on; the loader flattens both forms
 * into the same dotted property name, so the guard cannot be satisfied by formatting. (This was found
 * by planting the nested form during the D46 negative verification: the text-based version stayed
 * green.)</p>
 */
class DeploymentPrerequisiteTest {

    /** Full handbook text: the claim an operator follows. */
    private static String handbook;

    /** {@code docker-compose.yml}: owner of the MySQL server charset flags. */
    private static String compose;

    /** {@code pom.xml}: owner of the H2 dependency and its scope. */
    private static String pom;

    /** {@code docker/mysql/init/01-create-db.sql}: owner of the schema charset. */
    private static String initScript;

    /** {@code V1__create_med_message_shards.sql}: proves why the prerequisite exists. */
    private static String v1Migration;

    /** Every Spring configuration file ({@code application*}), mapped to its flattened property names. */
    private static Map<String, Set<String>> configurationProperties;

    @BeforeAll
    static void readDocuments() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        handbook = Files.readString(root.resolve("docs/DEPLOYMENT.md"));
        compose = Files.readString(root.resolve("docker-compose.yml"));
        pom = Files.readString(root.resolve("pom.xml"));
        initScript = Files.readString(root.resolve("docker/mysql/init/01-create-db.sql"));
        v1Migration = Files.readString(
                root.resolve("src/main/resources/db/migration/V1__create_med_message_shards.sql"));

        Map<String, Set<String>> loaded = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root.resolve("src/main/resources"))) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(DeploymentPrerequisiteTest::isConfigurationFile)
                    .sorted()
                    .toList()) {
                loaded.put(file.getFileName().toString(), propertyNames(file));
            }
        }
        configurationProperties = loaded;

        // Without these the H2-console scan could pass by loading nothing at all.
        assertThat(configurationProperties).isNotEmpty();
        assertThat(configurationProperties.get("application.yml"))
                .as("the scan must really read application.yml")
                .isNotNull()
                .contains("spring.application.name");
        assertThat(configurationProperties.keySet())
                .as("the scan must cover the profile files too, not only the base file")
                .contains("application-prod.yml");
    }

    private static boolean isConfigurationFile(Path path) {
        // Only Spring's own configuration files: they are the ones Boot loads from the classpath root,
        // and they are the only place an H2 console could be switched on. Other YAML under
        // src/main/resources (sharding/med-sharding.yaml) carries ShardingSphere's custom tags and is
        // not a Spring property source at all - loading it with SnakeYAML would fail on `!SHARDING`.
        String name = path.getFileName().toString();
        boolean yaml = name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
        return yaml && name.startsWith("application");
    }

    /**
     * Flattens one configuration file into the dotted property names the application would resolve,
     * so a nested YAML block and its dotted equivalent are indistinguishable here.
     */
    private static Set<String> propertyNames(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (name.endsWith(".properties")) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return new TreeSet<>(properties.stringPropertyNames());
        }
        Set<String> names = new TreeSet<>();
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load(name, new FileSystemResource(path))) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String property : enumerable.getPropertyNames()) {
                    names.add(property);
                }
            }
        }
        return names;
    }

    @Test
    @DisplayName("the handbook states the utf8mb4 prerequisite and how to verify it")
    void utf8mb4PrerequisiteIsDocumented() {
        // The operator must be able to answer: is my database charset good, and how do I check it?
        assertThat(handbook).contains("utf8mb4");
        assertThat(handbook).contains("character_set_server");
        assertThat(handbook).contains("SHOW CREATE DATABASE");
        // The Java-charset-name trap that makes the pool fail to build at all.
        assertThat(handbook).contains("characterEncoding");
        assertThat(handbook).contains("UTF-8");
        assertThat(handbook).contains("UnsupportedEncodingException");
    }

    @Test
    @DisplayName("the utf8mb4 claim is owned by compose and the init script, and the migration is why it is needed")
    void utf8mb4ClaimIsOwnedByTheStack() {
        // Compose pins the server charset; the init script pins the schema charset.
        assertThat(compose).contains("--character-set-server=utf8mb4");
        assertThat(compose).contains("--collation-server=utf8mb4_unicode_ci");
        assertThat(initScript).contains("CHARACTER SET utf8mb4");
        // And this is the reason the handbook has to state it: the DDL cannot pin a charset itself,
        // because the very same script runs against H2 in MySQL compatibility mode.
        assertThat(v1Migration).doesNotContain("DEFAULT CHARSET");
        assertThat(v1Migration).doesNotContain("ENGINE=");
    }

    @Test
    @DisplayName("no main configuration enables the H2 web console, in any YAML shape")
    void noConfigurationEnablesTheH2Console() {
        // The H2 console is an unauthenticated, in-process SQL console. H2 stays on the production
        // classpath for ShardingSphere's sake, so the console must be pinned off by absence - the
        // Spring Boot default only holds while no profile switches it on.
        configurationProperties.forEach((file, properties) -> properties.forEach(property ->
                assertThat(property)
                        .as("%s must not configure the H2 console", file)
                        .doesNotStartWith("spring.h2.console")));
        assertThat(handbook).contains("spring.h2.console");
    }

    @Test
    @DisplayName("H2 stays runtime-scoped for ShardingSphere and the pom says so")
    void h2StaysRuntimeScopedWithItsReason() {
        int artifactIndex = pom.indexOf("com.h2database");
        assertThat(artifactIndex).as("pom.xml must declare the H2 test double").isPositive();
        int dependencyEnd = pom.indexOf("</dependency>", artifactIndex);
        assertThat(dependencyEnd).isPositive();
        String h2Dependency = pom.substring(artifactIndex, dependencyEnd);
        // Narrowing this to `test` breaks ShardingSphere startup; broadening its use would be worse.
        assertThat(h2Dependency).contains("<scope>runtime</scope>");
        // The reason must stay next to the declaration, not only in the handbook.
        String precedingComment = pom.substring(Math.max(0, artifactIndex - 600), artifactIndex);
        assertThat(precedingComment).contains("ShardingSphere");
        assertThat(handbook).contains("ShardingSphere");
    }

    @Test
    @DisplayName("the handbook documents the H2 trade-off and its blast radius")
    void h2TradeOffIsDocumented() {
        assertThat(handbook).contains("H2");
        assertThat(handbook).contains("runtime");
        assertThat(handbook).contains("DeploymentPrerequisiteTest");
        // A prerequisite the operator cannot check is not a prerequisite.
        assertThat(handbook).contains("不允许开启");
    }

    @Test
    @DisplayName("both prerequisites are part of the pre-flight hardening checklist")
    void bothPrerequisitesAreOnTheHardeningChecklist() {
        int checklist = handbook.indexOf("## 10. 安全加固清单");
        assertThat(checklist).isPositive();
        String hardening = handbook.substring(checklist);
        assertThat(hardening).contains("H2 Web Console");
        assertThat(hardening).contains("utf8mb4");
    }
}
