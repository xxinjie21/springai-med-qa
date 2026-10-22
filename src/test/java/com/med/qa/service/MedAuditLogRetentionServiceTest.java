package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditLogRetentionProperties;
import com.med.qa.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedAuditLogRetentionService} (D55), the project's fifth background job and the first
 * whose deletions have no second copy.
 *
 * <p>Five behaviours carry the weight of this class and are pinned here:</p>
 * <ol>
 *   <li><b>Only rows before the cutoff are ever deleted.</b> The cutoff is asserted as an argument, not
 *       inferred from the report: a run that computed its window from the wrong clock or the wrong
 *       property would still produce a perfectly shaped report while trimming the wrong rows.</li>
 *   <li><b>Every delete is bounded.</b> The statement carries a {@code LIMIT}, the loop stops when a
 *       batch comes back short, and the number of passes never exceeds {@code max-batches} — an
 *       unbounded delete of the whole expired backlog in one transaction is the failure the batching
 *       exists to prevent.</li>
 *   <li><b>Dry run really is dry.</b> No delete — asserted as "the delete was never called", even though
 *       the run still measures, because its {@code deleted} figure is the exact simulation an operator
 *       arms the switch on.</li>
 *   <li><b>A failed batch ends the run.</b> Deliberately unlike the four session jobs: the exception
 *       propagates rather than being collected, and no further statement is attempted.</li>
 *   <li><b>The cluster mutex is respected.</b> A replica that does not get the lock must not read a
 *       single row, and it must report that as its own outcome rather than as "nothing expired".</li>
 * </ol>
 */
class MedAuditLogRetentionServiceTest {

    private static final long NOW = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli();

    private static final long CUTOFF = NOW - Duration.ofDays(365).toMillis();

    private AuditLogMapper auditLogMapper;

    private RedissonClient redissonClient;

    private RLock lock;

    private MedAuditLogRetentionProperties properties;

    private MedAuditLogRetentionService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        auditLogMapper = mock(AuditLogMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        properties = new MedAuditLogRetentionProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private MedAuditLogRetentionService serviceWith(Clock clock) {
        return new MedAuditLogRetentionService(auditLogMapper, redissonClient, properties, clock);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ the deleting run

    @Nested
    @DisplayName("a run that was allowed to delete")
    class DeletingRun {

        @Test
        @DisplayName("trims the expired rows in bounded batches and stops when a batch comes back short")
        void trimsInBoundedBatches() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF))
                    .thenReturn(1_200L)   // expired when the run began
                    .thenReturn(200L);    // still expired when it ended
            when(auditLogMapper.deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(500)))
                    .thenReturn(500, 200);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.outcome()).isEqualTo(AuditLogRetentionReport.Outcome.COMPLETED);
            assertThat(report.dryRun()).isFalse();
            assertThat(report.retentionDays()).isEqualTo(365);
            assertThat(report.expired()).isEqualTo(1_200L);
            assertThat(report.deleted()).isEqualTo(700L);
            assertThat(report.remaining()).isEqualTo(200L);
            assertThat(report.batches()).isEqualTo(2);
            assertThat(report.deletedAnything()).isTrue();
            // The third pass never happens: a short batch means the backlog is drained, and the run
            // ends with a fresh count instead of looping on empty statements.
            verify(auditLogMapper, times(2)).deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(500));
            verify(auditLogMapper, times(2)).countAuditLogsCreatedBefore(CUTOFF);
        }

        @Test
        @DisplayName("never issues more statements than max-batches, even when the backlog is deeper")
        void respectsMaxBatches() {
            service = serviceWith(fixedClock());
            properties.setBatchSize(100);
            properties.setMaxBatches(3);
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(10_000L);
            when(auditLogMapper.deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(100))).thenReturn(100);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.batches()).isEqualTo(3);
            assertThat(report.deleted()).isEqualTo(300L);
            verify(auditLogMapper, times(3)).deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(100));
        }

        @Test
        @DisplayName("a run with nothing expired performs no delete at all")
        void nothingExpiredMeansNoDelete() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(0L);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.expired()).isZero();
            assertThat(report.deleted()).isZero();
            assertThat(report.remaining()).isZero();
            assertThat(report.batches()).isZero();
            assertThat(report.deletedAnything()).isFalse();
            verify(auditLogMapper, never()).deleteAuditLogsCreatedBefore(anyLong(), anyInt());
        }

        @Test
        @DisplayName("the cutoff is derived from the configured window, so a longer window deletes less")
        void theCutoffFollowsTheConfiguredWindow() {
            service = serviceWith(fixedClock());
            properties.setRetentionDays(90);
            long expectedCutoff = NOW - Duration.ofDays(90).toMillis();
            when(auditLogMapper.countAuditLogsCreatedBefore(expectedCutoff)).thenReturn(0L);

            service.trimOnce();

            verify(auditLogMapper).countAuditLogsCreatedBefore(expectedCutoff);
            verify(auditLogMapper, never()).countAuditLogsCreatedBefore(CUTOFF);
        }

        @Test
        @DisplayName("a negative count from the mapper is clamped instead of producing a broken report")
        void negativeCountsAreClamped() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(-5L);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.expired()).isZero();
            assertThat(report.deleted()).isZero();
        }
    }

    // ------------------------------------------------------------------ the dry run

    @Nested
    @DisplayName("a dry run")
    class DryRun {

        @BeforeEach
        void armDryRun() {
            properties.setDryRun(true);
        }

        @Test
        @DisplayName("deletes nothing but reports the exact number a deleting run would remove")
        void reportsAnExactSimulation() {
            service = serviceWith(fixedClock());
            properties.setBatchSize(500);
            properties.setMaxBatches(5);
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(1_200L);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.dryRun()).isTrue();
            assertThat(report.expired()).isEqualTo(1_200L);
            assertThat(report.deleted()).isEqualTo(1_200L);
            assertThat(report.remaining()).isZero();
            assertThat(report.batches()).isEqualTo(3);
            verify(auditLogMapper, never()).deleteAuditLogsCreatedBefore(anyLong(), anyInt());
        }

        @Test
        @DisplayName("the simulation is capped by max-batches, exactly like the deleting loop")
        void theSimulationIsCappedToo() {
            service = serviceWith(fixedClock());
            properties.setBatchSize(500);
            properties.setMaxBatches(2);
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(3_000L);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.deleted()).isEqualTo(1_000L);
            assertThat(report.remaining()).isEqualTo(2_000L);
            assertThat(report.batches()).isEqualTo(2);
            verify(auditLogMapper, never()).deleteAuditLogsCreatedBefore(anyLong(), anyInt());
        }
    }

    // ------------------------------------------------------------------ failures

    @Nested
    @DisplayName("failure semantics")
    class Failures {

        @Test
        @DisplayName("a failed count aborts the run before any delete is attempted")
        void aFailedCountAbortsTheRun() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF))
                    .thenThrow(new DataAccessResourceFailureException("mysql is down"));

            assertThatThrownBy(() -> service.trimOnce())
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("failed to count the audit rows");
            verify(auditLogMapper, never()).deleteAuditLogsCreatedBefore(anyLong(), anyInt());
        }

        @Test
        @DisplayName("a failed batch ends the run: no further statement is attempted")
        void aFailedBatchEndsTheRun() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(10_000L);
            when(auditLogMapper.deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(500)))
                    .thenThrow(new DataAccessResourceFailureException("mysql is down"));

            assertThatThrownBy(() -> service.trimOnce())
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.STORAGE_ERROR))
                    .hasMessageContaining("failed to delete the audit rows");
            // Unlike the four session jobs, one bad batch is not collected and skipped: a partial
            // report would understate what was lost, and the next batch would likely fail too.
            verify(auditLogMapper, times(1)).deleteAuditLogsCreatedBefore(eq(CUTOFF), eq(500));
        }

        @Test
        @DisplayName("an unreachable Redis propagates instead of degrading to an unlocked run")
        void unreachableRedisPropagates() throws InterruptedException {
            service = serviceWith(fixedClock());
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenThrow(new RedisException("connection lost"));

            assertThatThrownBy(() -> service.trimOnce())
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.STORAGE_ERROR))
                    .hasMessageContaining("failed to acquire the audit log retention lock");
            verifyNoInteractions(auditLogMapper);
        }

        @Test
        @DisplayName("an interrupt while queuing is reported as an internal error with the flag restored")
        void anInterruptIsReported() throws InterruptedException {
            service = serviceWith(fixedClock());
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("stop");
                    });

            assertThatThrownBy(() -> service.trimOnce())
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INTERNAL_ERROR));
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the interrupt flag must be restored for the scheduler thread")
                    .isTrue();
            Thread.interrupted();
        }

        @Test
        @DisplayName("a failed release is left to the lease and does not fail the run")
        void aFailedReleaseDoesNotFailTheRun() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(0L);
            doThrow(new RedisException("connection lost")).when(lock).unlock();

            assertThat(service.trimOnce().outcome())
                    .isEqualTo(AuditLogRetentionReport.Outcome.COMPLETED);
        }
    }

    // ------------------------------------------------------------------ the mutex

    @Nested
    @DisplayName("the cluster mutex")
    class Mutex {

        @Test
        @DisplayName("a replica that loses the race reads nothing and says so")
        void losingTheRaceReadsNothing() throws InterruptedException {
            service = serviceWith(fixedClock());
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            AuditLogRetentionReport report = service.trimOnce();

            assertThat(report.outcome())
                    .isEqualTo(AuditLogRetentionReport.Outcome.SKIPPED_LOCK_HELD);
            assertThat(report.remaining()).isEqualTo(-1L);
            assertThat(report.retentionDays()).isEqualTo(365);
            verifyNoInteractions(auditLogMapper);
            verify(lock, never()).unlock();
        }

        @Test
        @DisplayName("the mutex key is the one the properties declare")
        void theMutexKeyIsTheDeclaredOne() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(0L);

            service.trimOnce();

            verify(redissonClient).getLock(MedAuditLogRetentionProperties.LOCK_KEY);
            assertThat(MedAuditLogRetentionProperties.LOCK_KEY)
                    .isEqualTo("med:lock:audit-log:retention");
        }

        @Test
        @DisplayName("the run releases the mutex it holds")
        void theRunReleasesTheMutex() throws InterruptedException {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(0L);

            service.trimOnce();

            InOrder inOrder = inOrder(lock, auditLogMapper);
            inOrder.verify(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
            inOrder.verify(auditLogMapper).countAuditLogsCreatedBefore(CUTOFF);
            inOrder.verify(lock).unlock();
        }
    }

    // ------------------------------------------------------------------ the preview and the constructor

    @Nested
    @DisplayName("the operator preview")
    class Preview {

        @Test
        @DisplayName("counts the expired rows without deleting or locking anything")
        void countsWithoutDeletingOrLocking() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF)).thenReturn(42L);

            assertThat(service.countExpiredAuditRows()).isEqualTo(42L);
            verify(auditLogMapper).countAuditLogsCreatedBefore(CUTOFF);
            verifyNoInteractions(redissonClient);
        }

        @Test
        @DisplayName("a failed preview is reported as a storage error")
        void aFailedPreviewIsReported() {
            service = serviceWith(fixedClock());
            when(auditLogMapper.countAuditLogsCreatedBefore(CUTOFF))
                    .thenThrow(new DataAccessResourceFailureException("mysql is down"));

            assertThatThrownBy(() -> service.countExpiredAuditRows())
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("failed to count the audit rows");
        }
    }

    @Nested
    @DisplayName("the constructor")
    class Constructor {

        @Test
        @DisplayName("rejects a missing collaborator instead of failing later on a timer")
        void rejectsAMissingCollaborator() {
            assertThatThrownBy(() -> new MedAuditLogRetentionService(null, redissonClient, properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("auditLogMapper");
            assertThatThrownBy(() -> new MedAuditLogRetentionService(auditLogMapper, null, properties))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("redissonClient");
            assertThatThrownBy(() -> new MedAuditLogRetentionService(auditLogMapper, redissonClient, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("properties");
            assertThatThrownBy(() -> new MedAuditLogRetentionService(auditLogMapper, redissonClient,
                    properties, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("clock");
        }
    }
}
