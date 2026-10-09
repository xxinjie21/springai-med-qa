package com.med.qa.config;

import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import com.med.qa.service.MedSessionArchiveExportScheduler;
import com.med.qa.service.MedSessionArchiveExportService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Wiring and configuration-contract tests for {@link MedSessionArchiveConfig} (D48).
 *
 * <h2>Why the yml half of this class exists</h2>
 * <p>The export is the third capability in this project whose {@code @Scheduled} delay is read straight
 * from a property placeholder ({@code ${med.session.archive.check-interval:6h}}). That placeholder has a
 * <em>fallback</em>, which means a renamed or deleted property does not fail the context - the job keeps
 * running on the annotation's literal default while the operator's configuration is silently ignored.
 * So the placeholders are read off the annotation by reflection, every key they name is required to exist
 * in {@code application.yml}, and the effective values are bound through Spring Boot's own
 * {@link YamlPropertySourceLoader} + {@link Binder} rather than grepped.</p>
 *
 * <p>The negative half is equally important: with {@code enabled} absent or {@code false} neither the
 * export nor its scheduler may exist, because this is a capability that copies clinical records and its
 * default has to be "do not".</p>
 */
class MedSessionArchiveConfigTest {

    private static final String PREFIX = "med.session.archive";

    /** Matches {@code ${key:fallback}} and {@code ${key}} placeholders. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

    private static Map<String, Object> applicationProperties;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MedSessionArchiveConfig.class, Collaborators.class);

    @BeforeAll
    static void loadApplicationYml() throws IOException {
        applicationProperties = flatten(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml"));
    }

    // ------------------------------------------------------------------ wiring

    @Test
    @DisplayName("the switch is off by default, so neither the export nor the scheduler exists")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedSessionArchiveExportService.class);
            assertThat(context).doesNotHaveBean(MedSessionArchiveExportScheduler.class);
        });
    }

    @Test
    @DisplayName("an explicit false is the same as an absent switch")
    void explicitlyDisabled() {
        runner.withPropertyValues(PREFIX + ".enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedSessionArchiveExportService.class);
            assertThat(context).doesNotHaveBean(MedSessionArchiveExportScheduler.class);
        });
    }

    @Test
    @DisplayName("enabling the switch contributes the export, the scheduler and the policy")
    void enabledWiresBothBeans() {
        runner.withPropertyValues(PREFIX + ".enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MedSessionArchiveProperties.class);
            assertThat(context.getBean(MedSessionArchiveConfig.SERVICE))
                    .isInstanceOf(MedSessionArchiveExportService.class);
            assertThat(context.getBean(MedSessionArchiveConfig.SCHEDULER))
                    .isInstanceOf(MedSessionArchiveExportScheduler.class);
        });
    }

    @Test
    @DisplayName("the context starts without a Redisson connection, because the client is lazy")
    void startsWithoutRedis() {
        runner.withPropertyValues(PREFIX + ".enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MedSessionArchiveConfig.SERVICE))
                    .isInstanceOf(MedSessionArchiveExportService.class);
        });
    }

    // ------------------------------------------------------------------ configuration contract

    @Test
    @DisplayName("the scheduled delays are read from properties that really exist")
    void scheduledPlaceholdersResolveToRealProperties() throws NoSuchMethodException {
        Scheduled scheduled = MedSessionArchiveExportScheduler.class
                .getMethod("scheduledExport")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled).as("the export must stay scheduled").isNotNull();

        for (String expression : new String[] {scheduled.fixedDelayString(), scheduled.initialDelayString()}) {
            Matcher matcher = PLACEHOLDER.matcher(expression);
            assertThat(matcher.find()).as("%s must be a placeholder", expression).isTrue();
            String key = matcher.group(1);
            assertThat(applicationProperties)
                    .as("%s must be declared in application.yml, otherwise the annotation's literal "
                            + "fallback silently takes over and the operator's value is ignored", key)
                    .containsKey(key);
        }
        assertThat(scheduled.fixedDelayString()).contains(PREFIX + ".check-interval");
        assertThat(scheduled.initialDelayString()).contains(PREFIX + ".initial-delay");
    }

    @Test
    @DisplayName("the effective policy keeps both switches conservative")
    void effectivePolicyIsConservative() {
        MedSessionArchiveProperties bound = bind();

        assertThat(bound.isEnabled())
                .as("the export must not be armed by the repository")
                .isFalse();
        assertThat(bound.isDryRun())
                .as("arming the export must still require a second decision")
                .isTrue();
        assertThat(bound.getBatchSize()).isEqualTo(50);
        assertThat(bound.getMaxBatches()).isEqualTo(5);
        assertThat(bound.getCheckInterval()).isEqualTo(Duration.ofHours(6));
        assertThat(bound.getInitialDelay()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("every archive key is declared, so binding cannot fall back to a Java default")
    void everyKeyIsDeclared() {
        // The bound value above could be the field initializer; these assertions pin the raw keys.
        assertThat(applicationProperties).containsKeys(
                PREFIX + ".enabled",
                PREFIX + ".dry-run",
                PREFIX + ".batch-size",
                PREFIX + ".max-batches",
                PREFIX + ".check-interval",
                PREFIX + ".initial-delay",
                PREFIX + ".lock-wait-time",
                PREFIX + ".lock-lease-time");
    }

    @Test
    @DisplayName("every archive switch has an environment variable, so a deployment can set it")
    void everyKeyIsOverridableFromTheEnvironment() {
        assertThat(applicationProperties.get(PREFIX + ".enabled"))
                .as("the master switch must be settable without editing the image")
                .isEqualTo("${MED_SESSION_ARCHIVE_ENABLED:false}");
        assertThat(applicationProperties.get(PREFIX + ".dry-run"))
                .isEqualTo("${MED_SESSION_ARCHIVE_DRY_RUN:true}");
        assertThat(applicationProperties.get(PREFIX + ".batch-size"))
                .isEqualTo("${MED_SESSION_ARCHIVE_BATCH_SIZE:50}");
        assertThat(applicationProperties.get(PREFIX + ".max-batches"))
                .isEqualTo("${MED_SESSION_ARCHIVE_MAX_BATCHES:5}");
    }

    @Test
    @DisplayName("the lock key of the export mutex stays inside the med:lock: namespace")
    void lockKeyIsNamespaced() {
        assertThat(MedSessionArchiveProperties.LOCK_KEY).startsWith("med:lock:");
        assertThat(MedSessionArchiveProperties.LOCK_KEY).isEqualTo("med:lock:session:archive:export");
    }

    // ------------------------------------------------------------------ helpers

    private static MedSessionArchiveProperties bind() {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        // Drop the ambient JVM sources: a stray -D on the build machine must not decide this contract.
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("application", applicationProperties));
        return Binder.get(environment)
                .bind(PREFIX, MedSessionArchiveProperties.class)
                .orElseGet(MedSessionArchiveProperties::new);
    }

    private static Map<String, Object> flatten(Path yamlFile) throws IOException {
        Map<String, Object> flattened = new LinkedHashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new FileSystemResource(yamlFile))) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flattened.put(name, enumerable.getProperty(name));
                }
            }
        }
        return flattened;
    }

    /** Test doubles for the collaborators the export needs, none of which may be touched here. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        ChatMessageMapper chatMessageMapper() {
            return mock(ChatMessageMapper.class);
        }

        @Bean
        SessionArchiveMapper sessionArchiveMapper() {
            return mock(SessionArchiveMapper.class);
        }

        @Bean
        ProtoMessageCodec protoMessageCodec() {
            return new ProtoMessageCodec();
        }

        @Bean
        RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }
    }
}
