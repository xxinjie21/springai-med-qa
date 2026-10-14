package com.med.qa.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime accounting for every background maintenance job in this application.
 *
 * <h2>Why this exists</h2>
 * <p>Each of the four maintenance jobs documents the same sentence: a job that stopped running looks
 * exactly like a hospital with nothing to clean up. Every one of them answered it with an alert that
 * fires <em>from inside the job</em>, so the sentence remained true -- a job that never runs produces
 * no failure alert and no completion notice, which means "healthy and idle" and "dead since March"
 * had byte-identical observability: no alert, no metric, no log line.</p>
 *
 * <p>This ledger publishes the only kind of signal that survives that: <em>the absence of data</em>.
 * A stale timestamp cannot be produced by a job that is not running, and it cannot be suppressed by a
 * broken alert chain either. That is why the four jobs record into it and why the Prometheus rules in
 * {@code deploy/prometheus/med-qa-alerts.yml} page on the staleness rather than on an event.</p>
 *
 * <h2>Two timestamps, not one</h2>
 * <ul>
 *   <li>{@value #LAST_RUN_GAUGE} -- when this instance last <em>attempted</em> the job. It advances for
 *       every outcome, including {@link MedJobOutcome#SKIPPED_LOCK_HELD}. That is deliberate: the four
 *       jobs share a cluster-wide mutex, so on a multi-replica deployment every replica but one steps
 *       aside on every cycle. Folding "stepped aside" into "did not run" would make this series report
 *       a healthy cluster as dead.</li>
 *   <li>{@value #LAST_SUCCESS_GAUGE} -- when this instance last <em>completed</em> the job's passes.
 *       Only {@link MedJobOutcome#COMPLETED} advances it. Its cluster-wide maximum is therefore the
 *       answer to a different question than the one above: not "is this pod's scheduler alive" but
 *       "is anybody actually doing the work". That is the only signal that catches a leaked cluster
 *       mutex -- a replica that acquired {@code med:lock:session:*} and never released it keeps every
 *       other replica in {@code SKIPPED_LOCK_HELD} forever, while {@value #LAST_RUN_GAUGE} keeps
 *       looking perfectly fresh.</li>
 * </ul>
 *
 * <h2>The expected interval is published as a metric</h2>
 * <p>{@value #EXPECTED_INTERVAL_GAUGE} carries the {@code check-interval} the deployment armed the job
 * with. A dead-man's switch has to be tolerant of a slow schedule: a deployment that sets
 * {@code check-interval: 7d} would be paged continuously by a rule that hard-codes "two hours without
 * a run". Publishing the configured interval lets the rule be written as
 * {@code time() - <stamp> > 2.5 * med_qa_job_expected_interval_seconds + 300}, which follows the
 * configuration instead of contradicting it.</p>
 *
 * <h2>Both timestamps start at the registration instant</h2>
 * <p>Not at zero. A freshly started instance has not run anything yet, and starting at the epoch would
 * make every rule that compares against {@code time()} fire for the whole {@code initial-delay} after
 * every restart -- the fastest way to teach an on-call engineer to ignore the alert. The cost is that a
 * job which never completes a run is reported after 2.5 intervals rather than immediately, which is
 * exactly the threshold a job whose scheduler never fires gets.</p>
 *
 * <p>What this design deliberately cannot see: a pod that restarts more often than the threshold resets
 * its own clock on every boot, so a crash-looping pod can keep both timestamps fresh while the job
 * never actually runs. That is inherent to an in-process heartbeat, and it is covered one level up --
 * the process is gone often enough that {@code up} and {@code MedQaTargetDown} catch it. The boundary is
 * documented in {@code docs/DEPLOYMENT.md} rather than left for a reader to discover.</p>
 *
 * <h2>What is out of scope</h2>
 * <p>The two alert monitors are not registered. "Is the alert producer alive" is a chicken-and-egg
 * question that no alert chain can answer about itself, so it stays with the process-level rule. And
 * there is no switch on this class: it is the only evidence that a job did not run, so an operator who
 * could switch it off would be switching off the observability of all four jobs at once.</p>
 */
public class MedJobRunLedger {

    /** Gauge holding the epoch second of the most recent attempt, per {@code job}. */
    public static final String LAST_RUN_GAUGE = "med_qa_job_last_run_epoch_seconds";

    /** Gauge holding the epoch second of the most recent run that completed its passes, per {@code job}. */
    public static final String LAST_SUCCESS_GAUGE = "med_qa_job_last_success_epoch_seconds";

    /** Gauge holding the configured {@code check-interval} in seconds, per {@code job}. */
    public static final String EXPECTED_INTERVAL_GAUGE = "med_qa_job_expected_interval_seconds";

    /** Counter of runs, tagged with {@code component} and {@code outcome}. */
    public static final String RUNS_COUNTER = "med_qa_job_runs_total";

    /**
     * Tag key carrying the job's stable identifier -- the same string the job uses as the
     * {@code component} label of its alerts.
     *
     * <p>Deliberately not called {@code job}, even though that is the domain word for it. Prometheus
     * reserves {@code job} and {@code instance} for the scrape target, and with the default
     * {@code honor_labels: false} a scraped label with one of those names is renamed to
     * {@code exported_job} while the target's own value takes its place. A rule selecting
     * {@code job=~"session-.*"} would therefore parse, scrape and match nothing at all -- the exact
     * silent-failure shape this ledger exists to remove. Reusing {@code component} also means the
     * selector names the same subsystem string the alert counter already carries, so one dashboard
     * covers both. {@code AlertingStackConfigTest} pins both halves: the tag name, and the fact that the
     * scrape config stays on the default label handling.</p>
     */
    public static final String COMPONENT_TAG = "component";

    /** Tag key carrying the {@link MedJobOutcome} name. */
    public static final String OUTCOME_TAG = "outcome";

    private final MeterRegistry meterRegistry;

    private final Clock clock;

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    /**
     * Creates the ledger over the application meter registry and the system clock.
     *
     * @param meterRegistry the application registry, must not be {@code null}
     * @throws IllegalArgumentException if {@code meterRegistry} is {@code null}
     */
    public MedJobRunLedger(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    /**
     * Creates the ledger with an explicit clock.
     *
     * <p>Exists so the "a freshly registered job is not immediately stale" contract can be tested
     * deterministically instead of by racing the wall clock.</p>
     *
     * @param meterRegistry the application registry, must not be {@code null}
     * @param clock         the clock used to seed the timestamps, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public MedJobRunLedger(MeterRegistry meterRegistry, Clock clock) {
        if (meterRegistry == null) {
            throw new IllegalArgumentException("meterRegistry must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Declares a job and publishes its meters.
     *
     * <p>Called once per job at assembly time. The call is <strong>idempotent</strong>, and that is a
     * contract rather than an optimisation: a second registration must not re-seed the timestamps,
     * because "register the job again" would otherwise be a way to make a stalled job look alive. The
     * first interval wins for the same reason -- the value the rule compares against has to be the one
     * the deployment actually armed.</p>
     *
     * @param job              the job's stable identifier, must not be {@code null} or blank
     * @param expectedInterval the {@code check-interval} the job was armed with, a positive duration
     * @throws IllegalArgumentException if {@code job} is {@code null} or blank, or if
     *                                  {@code expectedInterval} is {@code null}, zero or negative
     */
    public void register(String job, Duration expectedInterval) {
        if (job == null || job.isBlank()) {
            throw new IllegalArgumentException("job must not be blank");
        }
        if (expectedInterval == null || expectedInterval.isZero() || expectedInterval.isNegative()) {
            throw new IllegalArgumentException("expectedInterval must be a positive duration but was "
                    + expectedInterval);
        }
        jobs.computeIfAbsent(job, name -> publish(name, expectedInterval));
    }

    /**
     * Records one run.
     *
     * <p>Call this for <em>every</em> outcome, including the ones that did nothing. A run that is only
     * recorded when it succeeded would leave the attempt timestamp stale on a job that is running and
     * failing, which reports a scheduler that is alive as one that is not.</p>
     *
     * @param run the run to record, must not be {@code null} and must belong to a registered job
     * @return {@code run}, so callers can record and return in one expression
     * @throws IllegalArgumentException if {@code run} is {@code null}
     * @throws IllegalStateException    if the job was never registered; a job that reports without
     *                                  having been armed is a wiring defect, and failing here keeps the
     *                                  ledger's contents deliberate instead of discovered
     */
    public MedJobRun record(MedJobRun run) {
        if (run == null) {
            throw new IllegalArgumentException("run must not be null");
        }
        JobState state = jobs.get(run.job());
        if (state == null) {
            throw new IllegalStateException("job " + run.job() + " recorded a run without being "
                    + "registered; only a job a deployment deliberately armed may appear in the ledger");
        }
        long attemptEpochSeconds = run.startedAt().getEpochSecond();
        // Forward-only: a run recorded out of order (or after a clock that stepped backwards) must not
        // be able to make a healthy job look stalled.
        state.lastRunEpochSeconds.accumulateAndGet(attemptEpochSeconds, Math::max);
        if (run.completed()) {
            state.lastSuccessEpochSeconds.accumulateAndGet(attemptEpochSeconds, Math::max);
        }
        Counter.builder(RUNS_COUNTER)
                .description("Background job runs by outcome")
                .tag(COMPONENT_TAG, run.job())
                .tag(OUTCOME_TAG, run.outcome().name())
                .register(meterRegistry)
                .increment();
        return run;
    }

    /**
     * Returns the identifiers of every registered job.
     *
     * @return an unmodifiable snapshot, empty when nothing was registered
     */
    public Set<String> registeredJobs() {
        return Set.copyOf(jobs.keySet());
    }

    /**
     * Returns the epoch second of the most recent attempt of a job.
     *
     * @param job the job's identifier
     * @return the attempt instant, or {@code 0} when the job was never registered
     */
    public long lastRunEpochSeconds(String job) {
        JobState state = jobs.get(job);
        return state == null ? 0L : state.lastRunEpochSeconds.get();
    }

    /**
     * Returns the epoch second of the most recent run of a job that completed its passes.
     *
     * @param job the job's identifier
     * @return the completion instant, or {@code 0} when the job was never registered
     */
    public long lastSuccessEpochSeconds(String job) {
        JobState state = jobs.get(job);
        return state == null ? 0L : state.lastSuccessEpochSeconds.get();
    }

    /**
     * Returns the interval a job was armed with, as published in
     * {@value #EXPECTED_INTERVAL_GAUGE}.
     *
     * @param job the job's identifier
     * @return the configured {@code check-interval} in seconds, or {@code 0} when the job was never
     *         registered
     */
    public double expectedIntervalSeconds(String job) {
        JobState state = jobs.get(job);
        return state == null ? 0d : state.expectedIntervalSeconds;
    }

    /**
     * Publishes the three gauges of one job and returns its state.
     *
     * <p>Only ever reached from {@link #register} through {@link Map#computeIfAbsent}, so the meters are
     * created exactly once per job and a repeated registration cannot re-seed them.</p>
     *
     * @param job              the job's stable identifier
     * @param expectedInterval the configured interval
     * @return the fresh state, seeded to the current instant
     */
    private JobState publish(String job, Duration expectedInterval) {
        long nowEpochSeconds = clock.instant().getEpochSecond();
        JobState state = new JobState(nowEpochSeconds, nowEpochSeconds,
                expectedInterval.toMillis() / 1000d);
        Gauge.builder(LAST_RUN_GAUGE, state, s -> s.lastRunEpochSeconds.doubleValue())
                .description("Epoch second of the most recent attempt of this background job")
                .tag(COMPONENT_TAG, job)
                .register(meterRegistry);
        Gauge.builder(LAST_SUCCESS_GAUGE, state, s -> s.lastSuccessEpochSeconds.doubleValue())
                .description("Epoch second of the most recent run of this background job that "
                        + "completed its passes")
                .tag(COMPONENT_TAG, job)
                .register(meterRegistry);
        Gauge.builder(EXPECTED_INTERVAL_GAUGE, state, JobState::expectedIntervalSeconds)
                .description("Configured check-interval of this background job, in seconds")
                .tag(COMPONENT_TAG, job)
                .register(meterRegistry);
        return state;
    }

    /**
     * The three published values of one job.
     *
     * <p>The interval is a plain field because it is fixed at registration, while the two timestamps
     * are {@link AtomicLong}s because a scheduled thread writes them while the Micrometer scrape
     * thread reads them.</p>
     */
    private static final class JobState {

        private final AtomicLong lastRunEpochSeconds;

        private final AtomicLong lastSuccessEpochSeconds;

        private final double expectedIntervalSeconds;

        private JobState(long lastRunEpochSeconds, long lastSuccessEpochSeconds,
                         double expectedIntervalSeconds) {
            this.lastRunEpochSeconds = new AtomicLong(lastRunEpochSeconds);
            this.lastSuccessEpochSeconds = new AtomicLong(lastSuccessEpochSeconds);
            this.expectedIntervalSeconds = expectedIntervalSeconds;
        }

        private double expectedIntervalSeconds() {
            return expectedIntervalSeconds;
        }
    }
}
