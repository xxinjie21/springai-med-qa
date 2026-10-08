package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionRetentionProperties;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionRetentionService}, the job that makes the retention claim in
 * {@code MedChatSessionService#archiveSession}'s javadoc true.
 *
 * <p>Three behaviours carry the weight of this class and are therefore pinned here:</p>
 * <ol>
 *   <li><b>The cluster mutex is respected.</b> A replica that does not get the lock must not read a
 *       single row, and it must report that as its own outcome rather than as "nothing to do".</li>
 *   <li><b>Dry run really is dry.</b> No status update, no cache eviction — verified by asserting the
 *       mapper and the cache were never touched, not by reading the counters.</li>
 *   <li><b>The archive cannot misfire.</b> The staleness cutoff is handed to the compare-and-set
 *       statement itself, and a session that stopped being stale (or is busy with a turn) counts as
 *       skipped, never as archived and never as a failure.</li>
 * </ol>
 */
class MedSessionRetentionServiceTest {

    private static final String TENANT = "tenant-ret";

    private static final String DEPT = "dept-cardiology";

    private static final long NOW = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli();

    private static final long DAY = Duration.ofHours(24).toMillis();

    private ChatSessionMapper sessionMapper;

    private SessionLockService lockService;

    private RedisMessageCache cache;

    private RedissonClient redissonClient;

    private RLock lock;

    private MedSessionRetentionProperties properties;

    private MedSessionRetentionService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        sessionMapper = mock(ChatSessionMapper.class);
        lockService = mock(SessionLockService.class);
        cache = mock(RedisMessageCache.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        properties = new MedSessionRetentionProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service = new MedSessionRetentionService(sessionMapper, lockService, cache, redissonClient,
                properties, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("the staleness cutoff is now minus the configured idle threshold")
    void cutoffIsNowMinusTheIdleThreshold() {
        assertThat(service.idleBeforeEpochMillis()).isEqualTo(NOW - DAY);

        properties.setIdleThreshold(Duration.ofMinutes(30));
        assertThat(service.idleBeforeEpochMillis()).isEqualTo(NOW - Duration.ofMinutes(30).toMillis());
    }

    @Test
    @DisplayName("a sweep with no stale session completes without touching the cache")
    void emptySweepCompletes() {
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of());

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isZero();
        assertThat(report.archived()).isZero();
        assertThat(report.batches()).isZero();
        assertThat(report.failures()).isEmpty();
        assertThat(report.dryRun()).isFalse();
        assertThat(report.idleBeforeEpochMillis()).isEqualTo(NOW - DAY);
        verifyNoInteractions(cache);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a stale session is archived with the cutoff in the predicate and its window dropped")
    void archivesAStaleSession() {
        ChatSessionDO stale = session("session-stale", SessionStatus.ACTIVE, NOW - 2 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), eq(NOW - DAY), anyInt()))
                .thenReturn(List.of(stale));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong()))
                .thenReturn(1);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.COMPLETED);
        assertThat(report.archived()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.batches()).isEqualTo(1);

        // The cutoff is asserted by exact value on the archiving statement itself: a plain
        // `status = ACTIVE` compare-and-set would archive a session that was refreshed in between.
        verify(sessionMapper).updateStatusIfStale(eq("session-stale"), eq(SessionStatus.ARCHIVED),
                eq(SessionStatus.ACTIVE), eq(NOW - DAY), eq(NOW));
        verify(cache).evict(TENANT, DEPT, "session-stale");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a candidate that stopped being stale is skipped, never archived")
    void staleByTheTimeWeReachItIsSkipped() {
        ChatSessionDO raced = session("session-raced", SessionStatus.ACTIVE, NOW - 2 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(raced));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong()))
                .thenReturn(0);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.archived()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        verify(cache, never()).evict(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a session busy with a turn is skipped, not failed")
    void busySessionIsSkipped() {
        ChatSessionDO busy = session("session-busy", SessionStatus.ACTIVE, NOW - 3 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(busy));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenThrow(new BizException(ErrorCode.SESSION_LOCKED, "busy"));

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        verify(sessionMapper, never()).updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("a dry run reports what it found and changes nothing")
    void dryRunChangesNothing() {
        properties.setDryRun(true);
        ChatSessionDO stale = session("session-dry", SessionStatus.ACTIVE, NOW - 5 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(stale));
        when(sessionMapper.countStaleSessions(eq(SessionStatus.ACTIVE), anyLong())).thenReturn(7L);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.archived()).isZero();
        assertThat(report.batches()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(7);
        verify(sessionMapper, never()).updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong());
        verifyNoInteractions(cache);
        verifyNoInteractions(lockService);
    }

    @Test
    @DisplayName("a dry run performs a single pass, because nothing changed to advance the cursor")
    void dryRunPerformsOnePass() {
        properties.setDryRun(true);
        properties.setBatchSize(1);
        properties.setMaxBatches(5);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(session("session-a", SessionStatus.ACTIVE, NOW - 5 * DAY)));

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.batches()).isEqualTo(1);
        verify(sessionMapper, times(1)).selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt());
    }

    @Test
    @DisplayName("a full batch is followed by another pass until the backlog is drained")
    void keepsPassingWhileBatchesAreFull() {
        properties.setBatchSize(2);
        properties.setMaxBatches(4);
        ChatSessionDO first = session("session-1", SessionStatus.ACTIVE, NOW - 3 * DAY);
        ChatSessionDO second = session("session-2", SessionStatus.ACTIVE, NOW - 2 * DAY);
        ChatSessionDO third = session("session-3", SessionStatus.ACTIVE, NOW - 2 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(first, second), List.of(third));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong()))
                .thenReturn(1);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(3);
        assertThat(report.archived()).isEqualTo(3);
    }

    @Test
    @DisplayName("max-batches bounds one run and the remaining backlog is reported")
    void maxBatchesBoundsTheRun() {
        properties.setBatchSize(1);
        properties.setMaxBatches(2);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(session("session-1", SessionStatus.ACTIVE, NOW - 3 * DAY)));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong()))
                .thenReturn(1);
        when(sessionMapper.countStaleSessions(eq(SessionStatus.ACTIVE), anyLong())).thenReturn(9L);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.archived()).isEqualTo(2);
        assertThat(report.remaining()).isEqualTo(9);
    }

    @Test
    @DisplayName("one failing candidate never aborts the run")
    void aFailingCandidateDoesNotAbortTheSweep() {
        ChatSessionDO broken = session("session-broken", SessionStatus.ACTIVE, NOW - 4 * DAY);
        ChatSessionDO healthy = session("session-healthy", SessionStatus.ACTIVE, NOW - 4 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(broken, healthy));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(eq("session-broken"), any(), any(), anyLong(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));
        when(sessionMapper.updateStatusIfStale(eq("session-healthy"), any(), any(), anyLong(), anyLong()))
                .thenReturn(1);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.archived()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0)).contains("session-broken");
        verify(cache).evict(TENANT, DEPT, "session-healthy");
    }

    @Test
    @DisplayName("a failed cache eviction is a failure, not a silent half-archive")
    void aFailedEvictionIsReported() {
        ChatSessionDO stale = session("session-no-evict", SessionStatus.ACTIVE, NOW - 4 * DAY);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of(stale));
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        when(sessionMapper.updateStatusIfStale(anyString(), any(), any(), anyLong(), anyLong()))
                .thenReturn(1);
        when(cache.evict(TENANT, DEPT, "session-no-evict"))
                .thenThrow(new BizException(ErrorCode.STORAGE_ERROR, "redis down"));

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.archived()).isZero();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures().get(0)).contains("STORAGE_ERROR");
    }

    @Test
    @DisplayName("a replica that does not get the mutex does not read a single row")
    void lockHeldElsewhereSkipsTheRun() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(false);

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.archived()).isZero();
        assertThat(report.remaining()).isEqualTo(-1);
        verifyNoInteractions(sessionMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("an unreachable Redis propagates instead of degrading to an unlocked sweep")
    void unreachableRedisPropagates() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> service.sweepOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("retention sweep lock");
        verifyNoInteractions(sessionMapper);
    }

    @Test
    @DisplayName("a zero lease switches the acquisition to the watchdog form")
    void zeroLeaseUsesTheWatchdogForm() throws InterruptedException {
        properties.setLockLeaseTime(Duration.ZERO);
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of());

        service.sweepOnce();

        verify(lock).tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class));
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    @Test
    @DisplayName("an interrupted acquisition restores the interrupt flag and fails as INTERNAL_ERROR")
    void interruptedAcquisitionIsReported() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new InterruptedException("shutdown"));

        assertThatThrownBy(() -> service.sweepOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.interrupted())
                .as("the interrupt flag must be restored for the surrounding thread")
                .isTrue();
        verifyNoInteractions(sessionMapper);
    }

    @Test
    @DisplayName("a failed release is swallowed: the sweep it guarded already finished")
    void failedReleaseDoesNotMaskTheResult() {
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis went away"))
                .when(lock).unlock();

        SessionRetentionReport report = service.sweepOnce();

        assertThat(report.outcome()).isEqualTo(SessionRetentionReport.Outcome.COMPLETED);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("the read-only helpers are bounded and reject a non-positive limit")
    void readOnlyHelpers() {
        when(sessionMapper.countStaleSessions(eq(SessionStatus.ACTIVE), eq(NOW - DAY))).thenReturn(4L);
        assertThat(service.countStaleCandidates()).isEqualTo(4);

        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), eq(NOW - DAY), eq(5)))
                .thenReturn(List.of(session("session-x", SessionStatus.ACTIVE, NOW - 2 * DAY)));
        assertThat(service.findStaleCandidates(5)).hasSize(1);

        assertThatThrownBy(() -> service.findStaleCandidates(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    @Test
    @DisplayName("a storage failure while listing candidates propagates as STORAGE_ERROR")
    void listingFailurePropagates() {
        when(sessionMapper.selectStaleSessions(eq(SessionStatus.ACTIVE), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.sweepOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("stale sessions");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MedSessionRetentionService(null, lockService, cache,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionMapper");
        assertThatThrownBy(() -> new MedSessionRetentionService(sessionMapper, null, cache,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lockService");
        assertThatThrownBy(() -> new MedSessionRetentionService(sessionMapper, lockService, null,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cache");
        assertThatThrownBy(() -> new MedSessionRetentionService(sessionMapper, lockService, cache,
                null, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redissonClient");
        assertThatThrownBy(() -> new MedSessionRetentionService(sessionMapper, lockService, cache,
                redissonClient, null, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("properties");
        assertThatThrownBy(() -> new MedSessionRetentionService(sessionMapper, lockService, cache,
                redissonClient, properties, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status, long updatedAt) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId("patient-1");
        session.setStatus(status);
        session.setCreatedAt(updatedAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }
}
