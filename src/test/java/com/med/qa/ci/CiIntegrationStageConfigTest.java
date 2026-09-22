package com.med.qa.ci;

import static org.assertj.core.api.Assertions.assertThat;

import com.med.qa.integration.IntegrationTestRequirements;
import com.med.qa.integration.MedIntegrationImages;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guard tests for the container integration stage of the GitHub Actions workflow (D35).
 *
 * <p>The integration suite is the only part of the build that exercises real middleware, and it is
 * also the only part that can disappear without turning the build red. Before this iteration
 * {@code DockerAvailableCondition} disabled the whole suite whenever the Docker probe answered
 * {@code false} - which, on a current Docker Engine with an older Testcontainers, it always did - and
 * the two defects that followed (a missing {@code flyway-mysql} module, migrations driven through the
 * ShardingSphere proxy) shipped past every green build.</p>
 *
 * <p>A pipeline guarantee against that failure mode has to be asserted, not assumed, so these tests
 * parse {@code .github/workflows/ci.yml} and pin every lock the workflow relies on: the mandatory
 * Docker switch, the test-selection flags that fail when nothing is selected, the image layer cache
 * keyed on the very images the suite boots, and the absence of any {@code continue-on-error} escape
 * hatch.</p>
 */
class CiIntegrationStageConfigTest {

    /** Job id of the container integration stage. */
    private static final String JOB = "integration";

    /** Surefire pattern selecting the integration classes and nothing else. */
    private static final String INTEGRATION_TEST_PATTERN = "com.med.qa.integration.*IntegrationTest";

    private static Path workflowPath;
    private static Map<String, Object> workflow;
    private static String workflowText;
    private static Map<String, Object> job;
    private static List<Map<String, Object>> steps;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadWorkflow() throws IOException {
        workflowPath = Path.of(System.getProperty("user.dir"), ".github", "workflows", "ci.yml");
        workflowText = Files.readString(workflowPath);
        try (InputStream in = Files.newInputStream(workflowPath)) {
            workflow = new Yaml().load(in);
        }
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        job = (Map<String, Object>) jobs.get(JOB);
        steps = (List<Map<String, Object>>) job.get("steps");
    }

    @Test
    @DisplayName("the integration job exists and runs on a runner that ships a Docker daemon")
    void integrationJobRunsOnAContainerCapableRunner() {
        assertThat(job).as("ci.yml must declare an `%s` job", JOB).isNotNull();
        assertThat(job.get("name")).isEqualTo("Testcontainers integration stage");
        assertThat(job.get("runs-on"))
                .as("the stage is pointless on a runner without Docker")
                .isEqualTo("ubuntu-latest");
        assertThat(steps).isNotEmpty();
    }

    @Test
    @DisplayName("the job exports the mandatory-Docker switch, so a missing daemon fails instead of skipping")
    @SuppressWarnings("unchecked")
    void integrationJobExportsTheMandatoryDockerSwitch() {
        Map<String, Object> env = (Map<String, Object>) job.get("env");
        assertThat(env).as("the integration job must set an env block").isNotNull();
        assertThat(String.valueOf(env.get(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE)))
                .as("MED_TEST_INTEGRATION_REQUIRED must be truthy, or the suite can skip itself again")
                .isEqualTo("true");
        // The value has to stay quoted in YAML: an unquoted `true` is a boolean, and while
        // String.valueOf() would still read "true", a `-D...=` style injection would not.
        assertThat(workflowText).contains("MED_TEST_INTEGRATION_REQUIRED: 'true'");
    }

    @Test
    @DisplayName("the job runs only the integration classes and fails when none are selected")
    void integrationJobSelectsTheIntegrationClassesAndFailsWhenNoneMatch() {
        // Match on `-Dtest=` rather than `./mvnw`: the "grant execute permission" step also runs
        // ./mvnw, and picking that one up would make every assertion below vacuous.
        String run = runStepContaining("-Dtest=");

        assertThat(run).as("only the integration classes may run in this stage")
                .contains("-Dtest='" + INTEGRATION_TEST_PATTERN + "'");
        assertThat(run).as("a renamed or deleted integration class must fail the build")
                .contains("-Dsurefire.failIfNoSpecifiedTests=true");
        assertThat(run).as("an empty selection must fail the build")
                .contains("-DfailIfNoTests=true");
        assertThat(run).as("the coverage gate belongs to the verify job")
                .contains("-Djacoco.skip=true");
    }

    @Test
    @DisplayName("the job can never be marked continue-on-error, which would restore the silent skip")
    void integrationJobIsNeverAllowedToFailSilently() {
        assertThat(job.get("continue-on-error")).as("a red integration stage must block the change").isNull();
        // No step may smuggle the escape hatch back in either, and no other job in the workflow may
        // use it: a tolerated failure is exactly the silent skip this iteration removes.
        steps.forEach(step -> assertThat(step.get("continue-on-error"))
                .as("step `%s` must not tolerate a failure", step.get("name"))
                .isNull());
        assertThat(workflowText).doesNotContain("continue-on-error: true");
    }

    @Test
    @DisplayName("the stage uploads its Surefire reports even when the suite fails")
    @SuppressWarnings("unchecked")
    void integrationJobUploadsReportsOnFailure() {
        Map<String, Object> upload = steps.stream()
                .filter(step -> String.valueOf(step.get("uses")).startsWith("actions/upload-artifact"))
                .filter(step -> {
                    Object with = step.get("with");
                    return with instanceof Map<?, ?> map
                            && "integration-test-reports".equals(map.get("name"));
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the integration job must upload its reports as `integration-test-reports`"));

        assertThat(String.valueOf(upload.get("if")))
                .as("reports are only useful when the suite actually failed")
                .isEqualTo("always()");
        assertThat(String.valueOf(((Map<String, Object>) upload.get("with")).get("path")))
                .contains("target/surefire-reports");
    }

    @Test
    @DisplayName("the middleware image layer cache saves and loads the exact images the suite boots")
    @SuppressWarnings("unchecked")
    void middlewareImageCacheUsesTheImagesTheSuiteBoots() {
        // The pull/archive command must name the same coordinates as MedIntegrationImages, so a tag
        // bump cannot warm the cache with the wrong image.
        assertThat(workflowText)
                .as("the workflow must pull %s", MedIntegrationImages.MYSQL)
                .contains("docker pull " + MedIntegrationImages.MYSQL);
        assertThat(workflowText)
                .as("the workflow must pull %s", MedIntegrationImages.REDIS_STACK)
                .contains("docker pull " + MedIntegrationImages.REDIS_STACK);

        Map<String, Object> cacheStep = steps.stream()
                .filter(step -> String.valueOf(step.get("uses")).startsWith("actions/cache"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the integration job must cache the image layers"));
        assertThat(cacheStep.get("id")).isEqualTo("middleware-images");

        Map<String, Object> cacheWith = (Map<String, Object>) cacheStep.get("with");
        assertThat(String.valueOf(cacheWith.get("path"))).contains("med-qa-middleware-images.tar");
        String key = String.valueOf(cacheWith.get("key"));
        assertThat(key)
                .as("the cache key must change when a middleware tag changes")
                .contains(versionOf(MedIntegrationImages.MYSQL))
                .contains(versionOf(MedIntegrationImages.REDIS_STACK));

        // Restoring and saving are gated on the cache outcome, so a hit skips the pull entirely.
        Map<String, Object> loadStep = stepRunning("docker load");
        assertThat(String.valueOf(loadStep.get("if"))).contains("cache-hit == 'true'");
        Map<String, Object> saveStep = stepRunning("docker save");
        assertThat(String.valueOf(saveStep.get("if"))).contains("cache-hit != 'true'");
    }

    @Test
    @DisplayName("both READMEs document the mandatory-Docker switch an operator has to recognise")
    void readmesDocumentTheMandatoryDockerSwitch() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        String chinese = Files.readString(root.resolve("README.md"));
        String english = Files.readString(root.resolve("README.en.md"));

        assertThat(chinese).contains(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE);
        assertThat(english).contains(IntegrationTestRequirements.REQUIRED_ENV_VARIABLE);
        assertThat(chinese).contains(IntegrationTestRequirements.REQUIRED_PROPERTY);
        assertThat(english).contains(IntegrationTestRequirements.REQUIRED_PROPERTY);
    }

    @Test
    @DisplayName("boundary: the guard reads a real file, so a moved workflow cannot pass vacuously")
    void workflowFileIsReadFromDisk() {
        assertThat(Files.exists(workflowPath)).isTrue();
        assertThat(workflowText).contains("name: CI");
        assertThat(workflow.get("name")).isEqualTo("CI");
    }

    /** Text of the first step whose {@code run} block contains {@code fragment}. */
    private static String runStepContaining(String fragment) {
        return String.valueOf(stepRunning(fragment).get("run"));
    }

    /** The first step whose {@code run} block contains {@code fragment}. */
    private static Map<String, Object> stepRunning(String fragment) {
        return steps.stream()
                .filter(step -> String.valueOf(step.get("run")).contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no step runs a command containing: " + fragment));
    }

    /** The tag part of an {@code image:tag} coordinate, used to compare against the cache key. */
    private static String versionOf(String imageCoordinate) {
        return imageCoordinate.substring(imageCoordinate.indexOf(':') + 1);
    }
}
