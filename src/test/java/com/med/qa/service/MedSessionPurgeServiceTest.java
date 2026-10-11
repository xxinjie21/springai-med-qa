package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionPurgeProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionPurgeService}, the only job in the project that deletes clinical data.
 *
 * <p>Four behaviours carry the weight of this class and are pinned here:</p>
 * <ol>
 *   <li><b>Nothing is deleted without two independent proofs.</b> The cold copy must reproduce the
 *       manifest's digest <em>and</em> the live shard must reproduce it. Both refusals are asserted by
 *       checking that neither {@code deleteBySessionId} nor {@code markPurged} was ever called — not by
 *       reading the counters, which a bug could still get right while deleting.</li>
 *   <li><b>The cold copy is verified before the live shard is even read.</b> Until it reproduces the
 *       certification, the hot rows are the only copy of the transcript; the test asserts the read order
 *       by making the cold read fail and checking that the hot read never happened.</li>
 *   <li><b>Dry run really is dry.</b> No delete, no mark — asserted as "the mappers were never touched",
 *       even though the run performs the full verification so its report is worth reading.</li>
 *   <li><b>The cluster mutex is respected.</b> A replica that does not get the lock must not read a single
 *       row, and it must report that as its own outcome rather than as "nothing to do".</li>
 * </ol>
 */
class MedSessionPurgeServiceTest {

    private static final String TENANT = "tenant-purge";

    private static final String DEPT = "dept-cardiology";

    private static final String PATIENT = "patient-1";

    private static final long NOW = Instant.parse("2026-10-11T12:00:00Z").toEpochMilli();

    private static final long WEEK = Duration.ofDays(7).toMillis();

    private static final long ARCHIVED_AT = NOW - 30 * 24 * 3_600_000L;

    private ChatSessionMapper sessionMapper;

    private SessionArchiveMapper archiveMapper;

    private ChatMessageMapper messageMapper;

    private RedissonClient redissonClient;

    private RLock lock;

    private MedSessionPurgeProperties properties;

    private ProtoMessageCodec codec;

    private MedSessionPurgeService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        sessionMapper = mock(ChatSessionMapper.class);
        archiveMapper = mock(SessionArchiveMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        properties = new MedSessionPurgeProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);
        codec = new ProtoMessageCodec();

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service = new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper, codec,
                redissonClient, properties, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ read-only helpers

    @Test
    @DisplayName("the cooling-off cutoff is now minus the configured minimum archived age")
    void cutoffIsNowMinusTheCoolingOffWindow() {
        assertThat(service.archivedBeforeEpochMillis()).isEqualTo(NOW - WEEK);

        properties.setMinArchivedAge(Duration.ofHours(2));
        assertThat(service.archivedBeforeEpochMillis())
                .isEqualTo(NOW - Duration.ofHours(2).toMillis());
    }

    @Test
    @DisplayName("the read-only helpers are bounded and reject a non-positive limit")
    void readOnlyHelpers() {
        when(archiveMapper.countPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), eq(NOW - WEEK)))
                .thenReturn(6L);
        assertThat(service.countPurgeableCandidates()).isEqualTo(6);

        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), eq(NOW - WEEK),
                eq(5))).thenReturn(List.of(session("session-x")));
        assertThat(service.findPurgeableCandidates(5)).hasSize(1);

        assertThatThrownBy(() -> service.findPurgeableCandidates(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    @Test
    @DisplayName("a storage failure while listing candidates propagates as STORAGE_ERROR")
    void listingFailurePropagates() {
        when(archiveMapper.selectPurgeableArchivedSessions(any(), anyLong(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.purgeOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("awaiting a purge");
        verify(lock).unlock();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a certified session whose two copies agree has its hot rows deleted and is retired")
    void purgesACertifiedSession() {
        String sessionId = "session-purgeable";
        List<ChatMessageDO> transcript = transcript(sessionId, 3);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        String certified = TranscriptDigests.ofArchived(cold);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(manifest(sessionId, certified));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
        when(messageMapper.deleteBySessionId(sessionId)).thenReturn(3);
        when(sessionMapper.markPurged(eq(sessionId), eq(SessionStatus.ARCHIVED), eq(NOW))).thenReturn(1);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.purged()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.refused()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        assertThat(report.archivedBeforeEpochMillis()).isEqualTo(NOW - WEEK);
        verify(messageMapper).deleteBySessionId(sessionId);
        verify(sessionMapper).markPurged(sessionId, SessionStatus.ARCHIVED, NOW);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("the delete happens before the mark, so an interruption leaves a self-healing state")
    void deletesBeforeMarking() {
        String sessionId = "session-order";
        List<ChatMessageDO> transcript = transcript(sessionId, 1);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
        when(messageMapper.deleteBySessionId(sessionId)).thenReturn(1);
        when(sessionMapper.markPurged(anyString(), any(), anyLong())).thenReturn(1);

        service.purgeOnce();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(messageMapper, sessionMapper);
        order.verify(messageMapper).deleteBySessionId(sessionId);
        order.verify(sessionMapper).markPurged(sessionId, SessionStatus.ARCHIVED, NOW);
    }

    @Test
    @DisplayName("a certified session with no hot rows left is retired without deleting anything")
    void emptyHotTranscriptIsRetiredWithoutDeleting() {
        String sessionId = "session-already-gone";
        List<ChatMessageDO> transcript = transcript(sessionId, 2);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(List.of());
        when(sessionMapper.markPurged(anyString(), any(), anyLong())).thenReturn(1);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.purged()).isZero();
        assertThat(report.refused()).isZero();
        verify(messageMapper, never()).deleteBySessionId(anyString());
        verify(sessionMapper).markPurged(sessionId, SessionStatus.ARCHIVED, NOW);
    }

    @Test
    @DisplayName("a session whose shard rows vanished between the read and the delete is retired anyway")
    void vanishedRowsStillRetireTheSession() {
        String sessionId = "session-raced";
        List<ChatMessageDO> transcript = transcript(sessionId, 1);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
        when(messageMapper.deleteBySessionId(sessionId)).thenReturn(0);
        when(sessionMapper.markPurged(anyString(), any(), anyLong())).thenReturn(1);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.purged()).isZero();
        verify(sessionMapper).markPurged(sessionId, SessionStatus.ARCHIVED, NOW);
    }

    // ------------------------------------------------------------------ refusals

    @Nested
    @DisplayName("a session that cannot be accounted for is refused, never deleted")
    class Refusals {

        @Test
        @DisplayName("no manifest means nothing proves what the transcript was")
        void refusesWithoutAManifest() {
            String sessionId = "session-no-manifest";
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);

            SessionPurgeReport report = service.purgeOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.purged()).isZero();
            assertThat(report.failures()).hasSize(1);
            assertThat(report.failures().get(0)).contains(sessionId).contains("no export manifest");
            verifyNoInteractions(messageMapper);
            verify(sessionMapper, never()).markPurged(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a cold copy that does not reproduce the certification refuses before the shard is read")
        void refusesWhenTheColdCopyDiffers() {
            String sessionId = "session-cold-drift";
            List<ChatMessageDO> transcript = transcript(sessionId, 2);
            List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
            // The manifest certifies something else entirely: the cold store does not hold what it claims.
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId))
                    .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(
                            coldCopy(sessionId, transcript(sessionId, 5)))));
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);

            SessionPurgeReport report = service.purgeOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.failures().get(0)).contains("cold copy");
            // The ordering that matters: until the cold copy holds, the shard is the only copy there is,
            // so it must not even be read - let alone deleted.
            verify(messageMapper, never()).selectTranscriptBySessionId(anyString());
            verify(messageMapper, never()).deleteBySessionId(anyString());
            verify(sessionMapper, never()).markPurged(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a live shard that drifted away from its certification refuses rather than deleting")
        void refusesWhenTheShardDiffers() {
            String sessionId = "session-hot-drift";
            List<ChatMessageDO> certifiedTranscript = transcript(sessionId, 2);
            List<ArchivedMessageDO> cold = coldCopy(sessionId, certifiedTranscript);
            List<ChatMessageDO> drifted = transcript(sessionId, 3);
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId))
                    .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(drifted);

            SessionPurgeReport report = service.purgeOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.failures().get(0)).contains("live shard");
            // The drifted rows hold messages nobody certified; deleting them would destroy the only
            // record of them.
            verify(messageMapper, never()).deleteBySessionId(anyString());
            verify(sessionMapper, never()).markPurged(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a refused session stays a candidate, so the backlog does not silently shrink")
        void refusedSessionsStayCandidates() {
            String sessionId = "session-stuck";
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
            when(archiveMapper.countPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong()))
                    .thenReturn(1L);

            SessionPurgeReport report = service.purgeOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.remaining()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ dry run

    @Test
    @DisplayName("a dry run verifies everything, reports what it would do and changes nothing")
    void dryRunVerifiesButChangesNothing() {
        properties.setDryRun(true);
        String sessionId = "session-dry";
        List<ChatMessageDO> transcript = transcript(sessionId, 4);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
        when(archiveMapper.countPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong()))
                .thenReturn(9L);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.purged()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(9);
        // The verification really happened - that is what makes the report worth reading...
        verify(archiveMapper).selectManifest(sessionId);
        verify(archiveMapper).selectBySessionId(sessionId);
        verify(messageMapper).selectTranscriptBySessionId(sessionId);
        // ...and nothing was written.
        verify(messageMapper, never()).deleteBySessionId(anyString());
        verifyNoInteractions(sessionMapper);
    }

    @Test
    @DisplayName("a dry run reports the refusals too, so arming the job is an informed decision")
    void dryRunReportsRefusals() {
        properties.setDryRun(true);
        String sessionId = "session-dry-refused";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.refused()).isEqualTo(1);
        verifyNoInteractions(messageMapper, sessionMapper);
    }

    @Test
    @DisplayName("a dry run performs a single pass, because nothing changed to advance the cursor")
    void dryRunPerformsOnePass() {
        properties.setDryRun(true);
        properties.setBatchSize(1);
        properties.setMaxBatches(5);
        String sessionId = "session-dry-pass";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.batches()).isEqualTo(1);
        verify(archiveMapper, times(1)).selectPurgeableArchivedSessions(any(), anyLong(), anyInt());
    }

    // ------------------------------------------------------------------ batching

    @Test
    @DisplayName("an empty candidate set completes without touching the shards")
    void emptyRunCompletes() {
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong(), anyInt()))
                .thenReturn(List.of());

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isZero();
        assertThat(report.batches()).isZero();
        assertThat(report.failures()).isEmpty();
        verifyNoInteractions(messageMapper, sessionMapper);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a full batch is followed by another pass until the backlog is drained")
    void keepsPassingWhileBatchesAreFull() {
        properties.setBatchSize(2);
        properties.setMaxBatches(4);
        String first = "session-b1";
        String second = "session-b2";
        String third = "session-b3";
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong(), anyInt()))
                .thenReturn(List.of(session(first), session(second)), List.of(session(third)));
        for (String sessionId : List.of(first, second, third)) {
            List<ChatMessageDO> transcript = transcript(sessionId, 1);
            List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
            when(archiveMapper.selectManifest(sessionId))
                    .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
            when(messageMapper.deleteBySessionId(sessionId)).thenReturn(1);
        }
        when(sessionMapper.markPurged(anyString(), any(), anyLong())).thenReturn(1);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(3);
        assertThat(report.purged()).isEqualTo(3);
    }

    @Test
    @DisplayName("max-batches bounds one run and the remaining backlog is reported")
    void maxBatchesBoundsTheRun() {
        properties.setBatchSize(1);
        properties.setMaxBatches(2);
        String sessionId = "session-bounded";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.countPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong()))
                .thenReturn(11L);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.refused()).isEqualTo(2);
        assertThat(report.remaining()).isEqualTo(11);
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    @DisplayName("one failing candidate never aborts the run")
    void aFailingCandidateDoesNotAbortTheRun() {
        String broken = "session-broken";
        String healthy = "session-healthy";
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong(), anyInt()))
                .thenReturn(List.of(session(broken), session(healthy)));
        when(archiveMapper.selectManifest(broken))
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));
        List<ChatMessageDO> transcript = transcript(healthy, 1);
        List<ArchivedMessageDO> cold = coldCopy(healthy, transcript);
        when(archiveMapper.selectManifest(healthy))
                .thenReturn(manifest(healthy, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(healthy)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(healthy)).thenReturn(transcript);
        when(messageMapper.deleteBySessionId(healthy)).thenReturn(1);
        when(sessionMapper.markPurged(anyString(), any(), anyLong())).thenReturn(1);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.purged()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0)).contains(broken).contains("STORAGE_ERROR");
        verify(messageMapper).deleteBySessionId(healthy);
    }

    @Test
    @DisplayName("a delete that fails is a failure and leaves the session marked for the next run")
    void aFailingDeleteIsReported() {
        String sessionId = "session-no-delete";
        List<ChatMessageDO> transcript = transcript(sessionId, 1);
        List<ArchivedMessageDO> cold = coldCopy(sessionId, transcript);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(manifest(sessionId, TranscriptDigests.ofArchived(cold)));
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript);
        when(messageMapper.deleteBySessionId(sessionId))
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.purged()).isZero();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures().get(0)).contains("STORAGE_ERROR");
        verify(sessionMapper, never()).markPurged(anyString(), any(), anyLong());
    }

    // ------------------------------------------------------------------ mutex

    @Test
    @DisplayName("a replica that does not get the mutex does not read a single row")
    void lockHeldElsewhereSkipsTheRun() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(false);

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.purged()).isZero();
        assertThat(report.remaining()).isEqualTo(-1);
        assertThat(report.archivedBeforeEpochMillis()).isEqualTo(NOW - WEEK);
        verifyNoInteractions(archiveMapper, messageMapper, sessionMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("an unreachable Redis propagates instead of degrading to an unlocked purge")
    void unreachableRedisPropagates() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> service.purgeOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("purge lock");
        verifyNoInteractions(archiveMapper, messageMapper, sessionMapper);
    }

    @Test
    @DisplayName("a zero lease switches the acquisition to the watchdog form")
    void zeroLeaseUsesTheWatchdogForm() throws InterruptedException {
        properties.setLockLeaseTime(Duration.ZERO);
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong(), anyInt()))
                .thenReturn(List.of());

        service.purgeOnce();

        verify(lock).tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class));
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    @Test
    @DisplayName("an interrupted acquisition restores the interrupt flag and fails as INTERNAL_ERROR")
    void interruptedAcquisitionIsReported() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new InterruptedException("shutdown"));

        assertThatThrownBy(() -> service.purgeOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("interrupted");
        assertThat(Thread.interrupted())
                .as("the interrupt flag must be restored for the surrounding thread")
                .isTrue();
        verifyNoInteractions(archiveMapper, messageMapper, sessionMapper);
    }

    @Test
    @DisplayName("a failed release is swallowed: the run it guarded already finished")
    void failedReleaseDoesNotMaskTheResult() {
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), anyLong(), anyInt()))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("redis went away")).when(lock).unlock();

        SessionPurgeReport report = service.purgeOnce();

        assertThat(report.outcome()).isEqualTo(SessionPurgeReport.Outcome.COMPLETED);
        verify(lock).unlock();
    }

    // ------------------------------------------------------------------ constructor

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MedSessionPurgeService(null, archiveMapper, messageMapper, codec,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionMapper");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, null, messageMapper, codec,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiveMapper");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, archiveMapper, null, codec,
                redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messageMapper");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper,
                null, redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("codec");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper,
                codec, null, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redissonClient");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper,
                codec, redissonClient, null, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("properties");
        assertThatThrownBy(() -> new MedSessionPurgeService(sessionMapper, archiveMapper, messageMapper,
                codec, redissonClient, properties, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");
    }

    // ------------------------------------------------------------------ fixtures

    private void stubCandidate(String sessionId) {
        when(archiveMapper.selectPurgeableArchivedSessions(eq(SessionStatus.ARCHIVED), eq(NOW - WEEK),
                anyInt())).thenReturn(List.of(session(sessionId)));
    }

    private static ChatSessionDO session(String sessionId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId(PATIENT);
        session.setStatus(SessionStatus.ARCHIVED);
        session.setCreatedAt(ARCHIVED_AT);
        session.setUpdatedAt(ARCHIVED_AT);
        return session;
    }

    private static SessionArchiveManifestDO manifest(String sessionId, String checksum) {
        return new SessionArchiveManifestDO(sessionId, TENANT, DEPT, PATIENT, 1, checksum,
                ARCHIVED_AT + 1_000L);
    }

    /**
     * Builds a transcript with strictly increasing timestamps.
     *
     * <p>Explicit rather than derived from a clock on purpose: the canonical digest covers the
     * <em>sequence</em> of a transcript and both the cold read and the manifest are ordered by
     * {@code (created_at, message_id)}, so a fixture that handed out arbitrary timestamps would be
     * certified in one order and read back in another and the test would report a mismatch that says
     * nothing about the production path.</p>
     */
    private static List<ChatMessageDO> transcript(String sessionId, int count) {
        List<ChatMessageDO> transcript = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            transcript.add(ChatMessageDO.builder()
                    .messageId(sessionId + "-m" + index)
                    .sessionId(sessionId)
                    .tenantId(TENANT)
                    .deptId(DEPT)
                    .patientId(PATIENT)
                    .role(RoleType.PATIENT)
                    .content("turn " + index)
                    .tokenCount(4)
                    .masked(false)
                    .createdAt(ARCHIVED_AT + index * 1_000L)
                    .metadata(new LinkedHashMap<>(Map.of("source", "web")))
                    .build());
        }
        return transcript;
    }

    private List<ArchivedMessageDO> coldCopy(String sessionId, List<ChatMessageDO> transcript) {
        List<ArchivedMessageDO> rows = new ArrayList<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            rows.add(new ArchivedMessageDO(sessionId, message.getMessageId(), TENANT, DEPT, PATIENT,
                    message.getCreatedAt(), ARCHIVED_AT + 2_000L, codec.encodeMessage(message)));
        }
        return rows;
    }
}
