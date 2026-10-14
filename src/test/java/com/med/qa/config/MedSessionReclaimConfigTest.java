package com.med.qa.config;

import com.med.qa.job.MedJobRunLedger;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.MedLockProperties;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.service.MedSessionReclaimScheduler;
import com.med.qa.service.MedSessionReclaimService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Wiring and configuration-contract tests for {@link MedSessionReclaimConfig} (D52).
 *
 * <h2>Why the yml half of this class exists</h2>
 * <p>The reclaim is the fifth capability in this project whose {@code @Scheduled} delay is read straight
 * from a property placeholder ({@code ${med.session.reclaim.check-interval:12h}}). That placeholder has a
 * <em>fallback</em>, which means a renamed or deleted property does not fail the context — the job simply
 * keeps running on the annotation's literal default while the operator's configuration is silently
 * ignored. The same shape as the traps D33/D34 uncovered. So the placeholders are read off the annotation
 * by reflection and every key they name is required to exist in {@code application.yml}, and the effective
 * values are bound through Spring Boot's own {@link YamlPropertySourceLoader} + {@link Binder} rather than
 * grepped.</p>
 *
 * <p>The negative half is equally important: with {@code enabled} absent or {@code false} neither the
 * reclaim nor its scheduler may exist. Of every capability in the project this is one of only two whose
 * default has to be "do not touch clinical records", because it deletes them.</p>
 */
class MedSessionReclaimConfigTest {

    private static final String PREFIX = "med.session.reclaim";

    /** Matches {@code ${key:fallback}} and {@code ${key}} placeholders. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::[^}]*)?}");

    private static Map<String, Object> applicationProperties;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MedSessionReclaimConfig.class, Collaborators.class);

    @BeforeAll
    static void loadApplicationYml() throws IOException {
        applicationProperties = flatten(Path.of(System.getProperty("user.dir"),
                "src/main/resources/application.yml"));
    }

    // ------------------------------------------------------------------ wiring

    @Test
    @DisplayName("the switch is off by default, so neither the reclaim nor the scheduler exists")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedSessionReclaimService.class);
            assertThat(context).doesNotHaveBean(MedSessionReclaimScheduler.class);
        });
    }

    @Test
    @DisplayName("an explicit false is the same as an absent switch")
    void explicitlyDisabled() {
        runner.withPropertyValues(PREFIX + ".enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MedSessionReclaimService.class);
            assertThat(context).doesNotHaveBean(MedSessionReclaimScheduler.class);
        });
    }

    @Test
    @DisplayName("enabling the switch contributes the reclaim, the scheduler and the policy")
    void enabledWiresBothBeans() {
        runner.withPropertyValues(PREFIX + ".enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MedSessionReclaimProperties.class);
            assertThat(context.getBean(MedSessionReclaimConfig.SERVICE))
                    .isInstanceOf(MedSessionReclaimService.class);
            assertThat(context.getBean(MedSessionReclaimConfig.SCHEDULER))
                    .isInstanceOf(MedSessionReclaimScheduler.class);
        });
    }

    @Test
    @DisplayName("the context starts without a Redisson connection, because the client is lazy")
    void startsWithoutRedis() {
        runner.withPropertyValues(PREFIX + ".enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MedSessionReclaimConfig.SERVICE))
                    .isInstanceOf(MedSessionReclaimService.class);
        });
    }

    // ------------------------------------------------------------------ configuration contract

    @Test
    @DisplayName("the scheduled delays are read from properties that really exist")
    void scheduledPlaceholdersResolveToRealProperties() throws NoSuchMethodException {
        Scheduled scheduled = MedSessionReclaimScheduler.class
                .getMethod("scheduledReclaim")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled).as("the reclaim must stay scheduled").isNotNull();

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
    @DisplayName("the effective policy keeps both conservative knobs on the safe side")
    void effectivePolicyIsConservative() {
        MedSessionReclaimProperties bound = bind();

        assertThat(bound.isEnabled())
                .as("the reclaim must not be armed by the repository")
                .isFalse();
        assertThat(bound.isDryRun())
                .as("arming the reclaim must still require a second decision")
                .isTrue();
        assertThat(bound.getBatchSize()).isEqualTo(50);
        assertThat(bound.getMaxBatches()).isEqualTo(5);
        assertThat(bound.getCheckInterval()).isEqualTo(Duration.ofHours(12));
        assertThat(bound.getInitialDelay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("every reclaim key is declared, so binding cannot fall back to a Java default")
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
    @DisplayName("every reclaim switch has an environment variable, so a deployment can set it")
    void everyKeyIsOverridableFromTheEnvironment() {
        assertThat(applicationProperties.get(PREFIX + ".enabled"))
                .as("the master switch must be settable without editing the image")
                .isEqualTo("${MED_SESSION_RECLAIM_ENABLED:false}");
        assertThat(applicationProperties.get(PREFIX + ".dry-run"))
                .isEqualTo("${MED_SESSION_RECLAIM_DRY_RUN:true}");
        assertThat(applicationProperties.get(PREFIX + ".batch-size"))
                .isEqualTo("${MED_SESSION_RECLAIM_BATCH_SIZE:50}");
        assertThat(applicationProperties.get(PREFIX + ".max-batches"))
                .isEqualTo("${MED_SESSION_RECLAIM_MAX_BATCHES:5}");
    }

    @Test
    @DisplayName("there is deliberately no cooling-off window, unlike the purge")
    void thereIsNoCoolingOffWindow() {
        // The purge needs one because it destroys the ability to move a session back. This job destroys
        // nothing: the bytes stay in the live transcript. A window here would delay the release of space
        // while pretending to protect something, so its absence is part of the contract rather than an
        // oversight - and a key that silently did nothing would be worse than no key at all.
        assertThat(applicationProperties)
                .doesNotContainKey(PREFIX + ".min-archived-age")
                .doesNotContainKey(PREFIX + ".min-orphan-age");
    }

    @Test
    @DisplayName("the lock key of the reclaim mutex stays inside the med:lock: namespace")
    void lockKeyIsNamespaced() {
        assertThat(MedSessionReclaimProperties.LOCK_KEY).startsWith("med:lock:");
        assertThat(MedSessionReclaimProperties.LOCK_KEY).isEqualTo("med:lock:session:reclaim");
        assertThat(MedSessionReclaimProperties.LOCK_KEY)
                .as("sharing the purge's key would let the two jobs exclude each other for no reason")
                .isNotEqualTo(MedSessionPurgeProperties.LOCK_KEY);
    }

    @Test
    @DisplayName("the interval the ledger publishes is the bound check-interval, not the placeholder fallback")
    void theLedgerPublishesTheBoundInterval() {
        // The stall rule derives its threshold from the value the ledger publishes, so a drifted property
        // key would be doubly wrong: the scheduler would fire on the annotation's literal fallback while the
        // rule was tuned to something else. Passing an explicit value proves the ledger reads the bound
        // property rather than its own default.
        runner.withPropertyValues(PREFIX + ".enabled=true", PREFIX + ".check-interval=20m")
                .run(context -> assertThat(
                        context.getBean(MedJobRunLedger.class)
                                .expectedIntervalSeconds(MedSessionReclaimScheduler.COMPONENT))
                        .isEqualTo(1_200d));
    }

    // ------------------------------------------------------------------ helpers

    private static MedSessionReclaimProperties bind() {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        // Drop the ambient JVM sources: a stray -D on the build machine must not decide this contract.
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("application", applicationProperties));
        return Binder.get(environment)
                .bind(PREFIX, MedSessionReclaimProperties.class)
                .orElseGet(MedSessionReclaimProperties::new);
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

    /** Test doubles for the collaborators the reclaim needs, none of which may be touched here. */
    @Configuration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        ChatSessionMapper chatSessionMapper() {
            return mock(ChatSessionMapper.class);
        }

        @Bean
        SessionArchiveMapper sessionArchiveMapper() {
            return mock(SessionArchiveMapper.class);
        }

        @Bean
        ChatMessageMapper chatMessageMapper() {
            return mock(ChatMessageMapper.class);
        }

        @Bean
        RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }

        /**
         * Built for real (rather than mocked) because {@code startsWithoutRedis} is about the lock service
         * not touching Redisson at construction time — a mock would assert nothing.
         */
        @Bean
        SessionLockService sessionLockService(RedissonClient redissonClient) {
            return new SessionLockService(redissonClient, new MedLockProperties());
        }

        /**
         * The D53 run ledger the scheduler records into. Built for real over a plain registry: what the
         * wiring test asserts is the meter the ledger publishes, so a mock would assert nothing.
         */
        @Bean
        MedJobRunLedger medJobRunLedger() {
            return new MedJobRunLedger(new SimpleMeterRegistry());
        }
    }
}
