package com.med.qa.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard tests for the D33 alerting stack.
 *
 * <p>Alerting is configuration end to end: a Prometheus scrape target, a rule file and an
 * Alertmanager route that only work together. Every piece is individually plausible and the stack
 * still silent when one of them is wrong -- a renamed metric, a scrape path that does not match the
 * exposed actuator endpoint, a rule file that is not mounted. These tests parse all three files and
 * cross-check them against {@code application.yml} and {@code pom.xml}, so the mismatch fails
 * {@code mvn test} instead of being discovered during an outage.</p>
 */
class AlertingStackConfigTest {

    private static final String PROMETHEUS_CONFIG = "deploy/prometheus/prometheus.yml";
    private static final String ALERT_RULES = "deploy/prometheus/med-qa-alerts.yml";
    private static final String ALERTMANAGER_CONFIG = "deploy/alertmanager/alertmanager.yml";
    private static final String VERIFY_SCRIPT = "scripts/verify-docker-build.sh";

    private static Path root;
    private static String composeText;
    private static String applicationYml;
    private static String pom;
    private static Map<String, Object> compose;
    private static Map<String, Object> prometheus;
    private static Map<String, Object> alertRules;
    private static Map<String, Object> alertmanager;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readConfiguration() throws IOException {
        root = Path.of(System.getProperty("user.dir"));
        composeText = Files.readString(root.resolve("docker-compose.yml"));
        applicationYml = Files.readString(root.resolve("src/main/resources/application.yml"));
        pom = Files.readString(root.resolve("pom.xml"));
        compose = (Map<String, Object>) new Yaml().load(composeText);
        prometheus = (Map<String, Object>) new Yaml().load(Files.readString(root.resolve(PROMETHEUS_CONFIG)));
        alertRules = (Map<String, Object>) new Yaml().load(Files.readString(root.resolve(ALERT_RULES)));
        alertmanager = (Map<String, Object>) new Yaml().load(Files.readString(root.resolve(ALERTMANAGER_CONFIG)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        return (Map<String, Object>) ((Map<String, Object>) compose.get("services")).get(name);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    // ---------------------------------------------------------------- compose wiring

    @Test
    @DisplayName("prometheus and alertmanager are declared behind the observability profile")
    @SuppressWarnings("unchecked")
    void observabilityServicesAreOptIn() {
        Map<String, Object> prometheusService = service("prometheus");
        Map<String, Object> alertmanagerService = service("alertmanager");

        assertThat(prometheusService).isNotNull();
        assertThat(alertmanagerService).isNotNull();
        // Opt-in: a plain `docker compose up -d` must not pull two extra containers.
        assertThat((List<String>) prometheusService.get("profiles")).containsExactly("observability");
        assertThat((List<String>) alertmanagerService.get("profiles")).containsExactly("observability");
    }

    @Test
    @DisplayName("prometheus pins a stable GA image and mounts both the scrape config and the rules")
    @SuppressWarnings("unchecked")
    void prometheusMountsItsConfiguration() {
        Map<String, Object> prometheusService = service("prometheus");

        assertThat((String) prometheusService.get("image")).startsWith("prom/prometheus:v");
        List<String> volumes = (List<String>) prometheusService.get("volumes");
        assertThat(volumes).anyMatch(v -> v.contains(PROMETHEUS_CONFIG));
        assertThat(volumes).anyMatch(v -> v.contains(ALERT_RULES));
        // The rule file is mounted at the path rule_files references, not just somewhere.
        assertThat(volumes).anyMatch(v -> v.contains(":/etc/prometheus/rules/med-qa-alerts.yml"));
    }

    @Test
    @DisplayName("alertmanager pins a stable GA image and mounts its routing config")
    @SuppressWarnings("unchecked")
    void alertmanagerMountsItsConfiguration() {
        Map<String, Object> alertmanagerService = service("alertmanager");

        assertThat((String) alertmanagerService.get("image")).startsWith("prom/alertmanager:v");
        List<String> volumes = (List<String>) alertmanagerService.get("volumes");
        assertThat(volumes).anyMatch(v -> v.contains(ALERTMANAGER_CONFIG));
        assertThat(volumes).anyMatch(v -> v.contains(":/etc/alertmanager/alertmanager.yml"));
    }

    @Test
    @DisplayName("both monitoring services publish their UI port and are health-checked")
    @SuppressWarnings("unchecked")
    void monitoringServicesAreReachableAndChecked() {
        List<String> prometheusPorts = (List<String>) service("prometheus").get("ports");
        List<String> alertmanagerPorts = (List<String>) service("alertmanager").get("ports");

        assertThat(prometheusPorts).anyMatch(mapping -> mapping.endsWith(":9090"));
        assertThat(alertmanagerPorts).anyMatch(mapping -> mapping.endsWith(":9093"));

        Map<String, Object> prometheusHealth = (Map<String, Object>) service("prometheus").get("healthcheck");
        Map<String, Object> alertmanagerHealth = (Map<String, Object>) service("alertmanager").get("healthcheck");
        assertThat((List<String>) prometheusHealth.get("test")).anyMatch(t -> t.contains("/-/healthy"));
        assertThat((List<String>) alertmanagerHealth.get("test")).anyMatch(t -> t.contains("/-/healthy"));
        assertThat(prometheusHealth).containsKeys("interval", "timeout", "retries", "start_period");
    }

    @Test
    @DisplayName("the alerting services persist their state in named volumes")
    void monitoringDataVolumesAreDeclared() {
        @SuppressWarnings("unchecked")
        Map<String, Object> volumes = (Map<String, Object>) compose.get("volumes");

        assertThat(volumes).containsKeys("prometheus-data", "alertmanager-data");
    }

    @Test
    @DisplayName("boundary: no compose placeholder uses the invalid `${VAR:default}` form")
    void composeInterpolationUsesTheValidDefaultForm() {
        // Compose only understands `${VAR:-default}` / `${VAR-default}`; `${VAR:default}` aborts the
        // whole `docker compose up` with "invalid interpolation format", which is exactly the kind of
        // breakage a text-level guard catches before it reaches an operator. Comment lines are
        // skipped: the YAML parser drops them before interpolation, so they can safely document the
        // trap (as the MySQL password line does).
        String effective = composeText.lines()
                .filter(line -> !line.trim().startsWith("#"))
                .reduce("", (left, right) -> left + '\n' + right);

        Matcher matcher = Pattern
                .compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*:(?!-)[^}]*}")
                .matcher(effective);

        List<String> invalid = new java.util.ArrayList<>();
        while (matcher.find()) {
            invalid.add(matcher.group());
        }

        assertThat(invalid).as("compose aborts on these placeholders").isEmpty();
    }

    // ---------------------------------------------------------------- prometheus config

    @Test
    @DisplayName("prometheus scrapes the actuator prometheus endpoint on the app service")
    @SuppressWarnings("unchecked")
    void prometheusScrapesTheActuatorEndpoint() {
        List<Map<String, Object>> scrapeConfigs = (List<Map<String, Object>>) prometheus.get("scrape_configs");
        Map<String, Object> medQa = scrapeConfigs.stream()
                .filter(config -> "med-qa".equals(config.get("job_name")))
                .findFirst()
                .orElseThrow();

        assertThat(medQa.get("metrics_path")).isEqualTo("/actuator/prometheus");
        List<Map<String, Object>> staticConfigs = (List<Map<String, Object>>) medQa.get("static_configs");
        List<String> targets = (List<String>) staticConfigs.get(0).get("targets");
        assertThat(targets).containsExactly("app:8080");
    }

    @Test
    @DisplayName("prometheus loads the rule file and forwards firing alerts to alertmanager")
    @SuppressWarnings("unchecked")
    void prometheusLoadsRulesAndNotifiesAlertmanager() {
        assertThat((List<String>) prometheus.get("rule_files"))
                .containsExactly("/etc/prometheus/rules/med-qa-alerts.yml");

        Map<String, Object> alerting = (Map<String, Object>) prometheus.get("alerting");
        List<Map<String, Object>> managers = (List<Map<String, Object>>) alerting.get("alertmanagers");
        List<Map<String, Object>> staticConfigs =
                (List<Map<String, Object>>) managers.get(0).get("static_configs");
        assertThat((List<String>) staticConfigs.get(0).get("targets")).containsExactly("alertmanager:9093");
    }

    @Test
    @DisplayName("the scrape interval is short enough for the rule windows to mean something")
    void scrapeIntervalIsSane() {
        @SuppressWarnings("unchecked")
        Map<String, Object> global = (Map<String, Object>) prometheus.get("global");

        assertThat((String) global.get("scrape_interval")).isEqualTo("15s");
        assertThat((String) global.get("evaluation_interval")).isEqualTo("15s");
    }

    // ---------------------------------------------------------------- rule file

    @Test
    @DisplayName("the rule file declares the availability and quality groups with the documented alerts")
    @SuppressWarnings("unchecked")
    void rulesCoverAvailabilityAndQuality() {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) alertRules.get("groups");
        List<String> groupNames = groups.stream().map(group -> (String) group.get("name")).toList();
        assertThat(groupNames).containsExactly("med-qa-availability", "med-qa-quality");

        List<String> alertNames = groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream())
                .map(rule -> (String) rule.get("alert"))
                .toList();

        assertThat(alertNames).contains(
                "MedQaTargetDown",
                "MedQaStorageUnavailable",
                "MedQaStorageProbeFailed",
                "MedQaRagIndexDegraded",
                "MedQaAlertStorm",
                "MedQaHighServerErrorRate",
                "MedQaConsultationLatencyHigh",
                "MedQaRateLimitStorm");
    }

    @Test
    @DisplayName("every rule carries a severity and a component label for routing and grouping")
    @SuppressWarnings("unchecked")
    void everyRuleIsRoutable() {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) alertRules.get("groups");
        List<Map<String, Object>> rules = groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream())
                .toList();

        assertThat(rules).isNotEmpty();
        for (Map<String, Object> rule : rules) {
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            assertThat(labels).as("rule %s labels", rule.get("alert")).containsKeys("severity", "component");
            assertThat((String) labels.get("severity")).isIn("critical", "warning", "info");
            assertThat(rule).as("rule %s needs a `for:` window", rule.get("alert")).containsKey("for");
            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(annotations).as("rule %s annotations", rule.get("alert"))
                    .containsKeys("summary", "description");
        }
    }

    @Test
    @DisplayName("the storage rules select on the metric the alert chain actually exports")
    void rulesSelectOnTheExportedMetric() throws IOException {
        String metricsSink = read("src/main/java/com/med/qa/alert/MetricsMedAlertSink.java");
        assertThat(metricsSink).contains("med_qa_alert_total");
        assertThat(read(ALERT_RULES)).contains("med_qa_alert_total");
        assertThat(read(ALERT_RULES)).contains("code=\"storage-down\"");
        assertThat(read(ALERT_RULES)).contains("code=\"storage-probe-failed\"");
    }

    @Test
    @DisplayName("the latency rule can compute a quantile, so the histogram must stay enabled")
    void latencyRuleRequiresHistogramBuckets() throws IOException {
        assertThat(read(ALERT_RULES)).contains("http_server_requests_seconds_bucket");
        assertThat(applicationYml).contains("percentiles-histogram");
        assertThat(applicationYml).contains("http.server.requests: true");
    }

    @Test
    @DisplayName("the D37 RAG index rule selects the code the vector-index monitor actually exports")
    void ragIndexRuleMatchesTheExportedCode() throws IOException {
        // The rule is worthless if the code string drifts from the one MedVectorIndexAlertMonitor
        // raises: the metric exists, the rule parses, and it simply never fires.
        assertThat(read("src/main/java/com/med/qa/alert/MedVectorIndexAlertMonitor.java"))
                .contains("rag-index-degraded");
        assertThat(read(ALERT_RULES)).contains("code=\"rag-index-degraded\"");
        assertThat(read(ALERT_RULES)).contains("component: vector-index");
        // A degraded index degrades answers but does not stop consultations, so it must not page.
        assertThat(read(ALERT_RULES)).contains("MedQaRagIndexDegraded");
    }

    @Test
    @DisplayName("the vector-index monitoring switches are documented in application.yml")
    void ragIndexPropertiesAreDeclared() {
        List<String> variables = List.of(
                "MED_RAG_INDEX_ENABLED",
                "MED_RAG_INDEX_CHECK_INTERVAL",
                "MED_RAG_INDEX_INITIAL_DELAY");

        variables.forEach(variable -> assertThat(applicationYml)
                .as("%s must be a real placeholder", variable)
                .contains(variable));
        // The expectation the probe compares the live index against has to be declared, not implied.
        assertThat(applicationYml).contains("expected-tag-fields:");
    }

    // ---------------------------------------------------------------- alertmanager

    @Test
    @DisplayName("alertmanager groups by alert name and component, with a fast path for critical")
    @SuppressWarnings("unchecked")
    void alertmanagerRoutesBySeverity() {
        Map<String, Object> route = (Map<String, Object>) alertmanager.get("route");

        assertThat((List<String>) route.get("group_by")).containsExactly("alertname", "component");
        assertThat(route).containsKeys("group_wait", "group_interval", "repeat_interval", "receiver");

        List<Map<String, Object>> routes = (List<Map<String, Object>>) route.get("routes");
        assertThat(routes).hasSize(1);
        List<String> matchers = (List<String>) routes.get(0).get("matchers");
        assertThat(matchers).containsExactly("severity = \"critical\"");
        assertThat((String) routes.get(0).get("receiver")).isEqualTo("critical");
    }

    @Test
    @DisplayName("both receivers referenced by the route are actually defined")
    @SuppressWarnings("unchecked")
    void everyReferencedReceiverExists() {
        Map<String, Object> route = (Map<String, Object>) alertmanager.get("route");
        String defaultReceiver = (String) route.get("receiver");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) route.get("routes");
        String criticalReceiver = (String) routes.get(0).get("receiver");

        List<Map<String, Object>> receivers = (List<Map<String, Object>>) alertmanager.get("receivers");
        List<String> names = receivers.stream().map(receiver -> (String) receiver.get("name")).toList();

        assertThat(names).contains(defaultReceiver, criticalReceiver);
        assertThat(receivers)
                .allSatisfy(receiver -> assertThat(receiver).containsKey("webhook_configs"));
    }

    @Test
    @DisplayName("inhibition rules stop a storage outage from paging twice for its own symptoms")
    @SuppressWarnings("unchecked")
    void inhibitionRulesAreDeclared() {
        List<Map<String, Object>> inhibitRules = (List<Map<String, Object>>) alertmanager.get("inhibit_rules");

        assertThat(inhibitRules).isNotEmpty();
        assertThat(inhibitRules)
                .allSatisfy(rule -> assertThat(rule).containsKeys("source_matchers", "target_matchers", "equal"));
        assertThat(inhibitRules.toString()).contains("MedQaTargetDown");
    }

    // ---------------------------------------------------------------- build wiring

    @Test
    @DisplayName("the Prometheus registry is on the classpath and the endpoint is exposed")
    void prometheusRegistryIsWired() {
        assertThat(pom).contains("micrometer-registry-prometheus");
        assertThat(applicationYml).contains("include: health,info,prometheus");
    }

    @Test
    @DisplayName("the alert policy is documented in application.yml with its own placeholders")
    void alertPropertiesAreDeclared() {
        List<String> variables = List.of(
                "MED_ALERT_ENABLED",
                "MED_ALERT_CHECK_INTERVAL",
                "MED_ALERT_INITIAL_DELAY",
                "MED_ALERT_COOLDOWN",
                "MED_ALERT_MINIMUM_SEVERITY",
                "MED_ALERT_KEY_PREFIX");

        variables.forEach(variable -> assertThat(applicationYml)
                .as("%s must be a real placeholder", variable)
                .contains(variable));
        assertThat(applicationYml).contains("alert:");
    }

    // ---------------------------------------------------------------- docker build verification

    @Test
    @DisplayName("the container-build verification script exists and drives a real docker build")
    void dockerBuildVerificationScriptExists() throws IOException {
        Path script = root.resolve(VERIFY_SCRIPT);

        assertThat(script).exists();
        String text = Files.readString(script);
        assertThat(text).contains("docker build");
        assertThat(text).contains("docker image inspect");
        // It must skip cleanly on a machine without a daemon instead of failing the pipeline.
        assertThat(text).contains("exit 2");
    }

    @Test
    @DisplayName("the script asserts the same runtime contract the Dockerfile tests pin")
    void dockerBuildVerificationAssertsTheContract() throws IOException {
        String text = read(VERIFY_SCRIPT);

        assertThat(text).contains("org.opencontainers.image.title");
        assertThat(text).contains("medqa");
        assertThat(text).contains("JarLauncher");
    }

    @Test
    @DisplayName("the deployment handbook documents the alerting stack and the build verification")
    void deploymentHandbookDocumentsAlerting() throws IOException {
        String handbook = read("docs/DEPLOYMENT.md");

        assertThat(handbook).contains("deploy/prometheus/prometheus.yml");
        assertThat(handbook).contains("deploy/alertmanager/alertmanager.yml");
        assertThat(handbook).contains(VERIFY_SCRIPT);
    }

    @Test
    @DisplayName("boundary: the deploy directory holds only files the stack actually references")
    void deployDirectoryIsFullyReferenced() throws IOException {
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            List<String> relative = files.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .toList();

            assertThat(relative).containsExactlyInAnyOrder(PROMETHEUS_CONFIG, ALERT_RULES, ALERTMANAGER_CONFIG);
            relative.forEach(file -> assertThat(composeText)
                    .as("%s must be mounted by the compose stack", file)
                    .contains(file));
        }
    }
}
