package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionReclaimProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionReclaimService} (D52), the second job in the project that deletes clinical
 * data.
 *
 * <p>Five behaviours carry the weight of this class and are pinned here:</p>
 * <ol>
 *   <li><b>Nothing is deleted without the containment proof.</b> Every message in the cold copy must still
 *       exist in the live transcript. Both refusal cases (one message missing, and a live transcript that
 *       is empty while the copy is not) are asserted by checking that {@code deleteColdCopy} was never
 *       called — not by reading the counters, which a bug could still get right while deleting.</li>
 *   <li><b>A certification makes the copy evidence again.</b> The manifest is re-read <em>inside</em> the
 *       session lock, because the candidate query ran outside it; a session archived and exported in
 *       between must not lose the rows its certification describes. The cold copy must not even be read
 *       in that case.</li>
 *   <li><b>A cold session is not an orphan.</b> Its uncertified cold copy is the export's work in
 *       progress, so the status is re-read inside the lock and an {@code ARCHIVED} candidate is skipped
 *       with zero reads of the archive.</li>
 *   <li><b>Dry run really is dry.</b> No delete — asserted as "the delete was never called", even though
 *       the run performs the full verification so its report is worth reading.</li>
 *   <li><b>The cluster mutex is respected.</b> A replica that does not get the lock must not read a single
 *       row, and it must report that as its own outcome rather than as "nothing to do".</li>
 * </ol>
 */
class MedSessionReclaimServiceTest {

    private static final String TENANT = "tenant-reclaim";

    private static final String DEPT = "dept-cardiology";

    private static final String PATIENT = "patient-1";

    private static final long NOW = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();

    private static final long TOUCHED_AT = NOW - 3_600_000L;

    /** A real SHA-256 shape: the manifest constructor validates it, and a fake one throws mid-stubbing. */
    private static final String DIGEST =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private ChatSessionMapper sessionMapper;

    private SessionArchiveMapper archiveMapper;

    private ChatMessageMapper messageMapper;

    private RedissonClient redissonClient;

    private RLock lock;

    private SessionLockService lockService;

    private MedSessionReclaimProperties properties;

    private MedSessionReclaimService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        sessionMapper = mock(ChatSessionMapper.class);
        archiveMapper = mock(SessionArchiveMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        lockService = mock(SessionLockService.class);
        properties = new MedSessionReclaimProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        // The session lock runs its action inline, and the in-lock status re-read sees a live row: the
        // default candidate is one a restore has already moved back. Both defaults are overridden by the
        // tests that pin the lock and the re-read themselves.
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(lockService).executeLocked(any(ChatSessionDO.class), any());
        when(sessionMapper.selectById(anyString()))
                .thenAnswer(invocation -> session(invocation.getArgument(0), SessionStatus.ACTIVE));

        service = new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper, lockService,
                redissonClient, properties, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ read-only helpers

    @Test
    @DisplayName("the read-only helpers are bounded and reject a non-positive limit")
    void readOnlyHelpers() {
        when(archiveMapper.countReclaimableOrphans(eq(SessionStatus.ARCHIVED))).thenReturn(6L);
        assertThat(service.countReclaimableOrphans()).isEqualTo(6);

        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), eq(5)))
                .thenReturn(List.of(session("session-x", SessionStatus.ACTIVE)));
        assertThat(service.findReclaimableOrphans(5)).hasSize(1);

        assertThatThrownBy(() -> service.findReclaimableOrphans(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    @Test
    @DisplayName("a storage failure while listing candidates propagates as STORAGE_ERROR")
    void listingFailurePropagates() {
        when(archiveMapper.selectReclaimableOrphans(any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.reclaimOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cold copy was orphaned");
        verify(lock).unlock();
    }

    @Test
    @DisplayName("a failure while counting the backlog propagates too, so the report cannot lie")
    void countingFailurePropagates() {
        when(archiveMapper.selectReclaimableOrphans(any(), anyInt()))
                .thenReturn(List.of(session("session-count", SessionStatus.ACTIVE)));
        when(archiveMapper.countReclaimableOrphans(any()))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.reclaimOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("failed to count");
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("an orphaned cold copy whose messages all survive in the live transcript is deleted")
    void reclaimsAnOrphanedColdCopy() {
        String sessionId = "session-orphaned";
        List<ChatMessageDO> live = transcript(sessionId, 3);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
        when(archiveMapper.deleteColdCopy(sessionId)).thenReturn(3);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.reclaimed()).isEqualTo(1);
        assertThat(report.reclaimedMessages()).isEqualTo(3);
        assertThat(report.skipped()).isZero();
        assertThat(report.refused()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        verify(archiveMapper).deleteColdCopy(sessionId);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("the live transcript may hold more than the copy: the copy is a prefix, not the whole")
    void reclaimsACopyTheLiveTranscriptHasOutgrown() {
        String sessionId = "session-grown";
        List<ChatMessageDO> certified = transcript(sessionId, 2);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, certified));
        // The session was restored and has been written to since, which is exactly the normal state of an
        // orphan: the copy is a snapshot of an earlier moment of a transcript that kept growing.
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(transcript(sessionId, 5));
        when(archiveMapper.deleteColdCopy(sessionId)).thenReturn(2);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.reclaimed()).isEqualTo(1);
        assertThat(report.reclaimedMessages()).isEqualTo(2);
        verify(archiveMapper).deleteColdCopy(sessionId);
    }

    @Test
    @DisplayName("a delete that raced with another run reports skipped rather than a phantom reclaim")
    void aRacedDeleteReportsSkipped() {
        String sessionId = "session-raced";
        List<ChatMessageDO> live = transcript(sessionId, 1);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
        when(archiveMapper.deleteColdCopy(sessionId)).thenReturn(0);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.reclaimed()).isZero();
        assertThat(report.reclaimedMessages()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
    }

    @Test
    @DisplayName("the delete happens inside the session lock, after the containment proof")
    void deletesInsideTheSessionLock() {
        String sessionId = "session-order";
        List<ChatMessageDO> live = transcript(sessionId, 1);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
        when(archiveMapper.deleteColdCopy(sessionId)).thenReturn(1);

        service.reclaimOnce();

        InOrder order = inOrder(archiveMapper, lockService, messageMapper);
        order.verify(archiveMapper).selectReclaimableOrphans(any(), anyInt());
        order.verify(lockService).executeLocked(any(ChatSessionDO.class), any());
        order.verify(messageMapper).selectTranscriptBySessionId(sessionId);
        order.verify(archiveMapper).deleteColdCopy(sessionId);
    }

    // ------------------------------------------------------------------ candidates that stop being candidates

    @Test
    @DisplayName("a copy that is already gone is skipped, so the predicate itself is the completion signal")
    void skipsAnAlreadyGoneCopy() {
        String sessionId = "session-already-reclaimed";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(List.of());

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.reclaimed()).isZero();
        assertThat(report.failed()).isZero();
        verify(archiveMapper, never()).deleteColdCopy(anyString());
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("a certification that appeared after the candidate query turns the copy back into evidence")
    void skipsACertifiedCopy() {
        String sessionId = "session-certified-again";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId))
                .thenReturn(new SessionArchiveManifestDO(sessionId, TENANT, DEPT, PATIENT, 2, DIGEST,
                        TOUCHED_AT));

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.reclaimed()).isZero();
        // The rows are evidence now, so the copy must not even be read - let alone deleted.
        verify(archiveMapper, never()).selectBySessionId(anyString());
        verify(archiveMapper, never()).deleteColdCopy(anyString());
        verifyNoInteractions(messageMapper);
    }

    @Test
    @DisplayName("a candidate that became cold again is left to the export, with zero archive reads")
    void skipsACandidateThatWentColdAgain() {
        String sessionId = "session-cold-again";
        stubCandidate(sessionId);
        when(sessionMapper.selectById(sessionId)).thenReturn(session(sessionId, SessionStatus.ARCHIVED));

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.reclaimed()).isZero();
        verify(archiveMapper, never()).selectManifest(anyString());
        verify(archiveMapper, never()).deleteColdCopy(anyString());
    }

    @Test
    @DisplayName("a candidate whose row disappeared is skipped too")
    void skipsACandidateWhoseRowDisappeared() {
        String sessionId = "session-vanished";
        stubCandidate(sessionId);
        when(sessionMapper.selectById(sessionId)).thenReturn(null);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        verify(archiveMapper, never()).deleteColdCopy(anyString());
    }

    @Test
    @DisplayName("a busy session lock is a skipped outcome, not a failure")
    void skipsABusySession() {
        String sessionId = "session-busy";
        stubCandidate(sessionId);
        doThrow(new BizException(ErrorCode.SESSION_LOCKED, "busy"))
                .when(lockService).executeLocked(any(ChatSessionDO.class), any());

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).isEmpty();
        verify(archiveMapper, never()).selectManifest(anyString());
        verify(archiveMapper, never()).deleteColdCopy(anyString());
    }

    @Test
    @DisplayName("the status is re-read inside the session lock, after the candidate query")
    void reReadsTheStatusInsideTheLock() {
        String sessionId = "session-locked-then-read";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(List.of());

        service.reclaimOnce();

        InOrder order = inOrder(archiveMapper, lockService, sessionMapper);
        order.verify(archiveMapper).selectReclaimableOrphans(any(), anyInt());
        order.verify(lockService).executeLocked(any(ChatSessionDO.class), any());
        order.verify(sessionMapper).selectById(sessionId);
    }

    // ------------------------------------------------------------------ refusals

    @Nested
    @DisplayName("a copy that holds something the live transcript does not is refused, never deleted")
    class Refusals {

        @Test
        @DisplayName("a single message the live transcript lacks is enough to refuse")
        void refusesWhenAMessageIsMissingFromTheLiveTranscript() {
            String sessionId = "session-missing-message";
            List<ChatMessageDO> live = transcript(sessionId, 2);
            List<ArchivedMessageDO> cold = new ArrayList<>(coldCopy(sessionId, live));
            cold.add(new ArchivedMessageDO(sessionId, "gone-message", TENANT, DEPT, PATIENT, TOUCHED_AT,
                    TOUCHED_AT, new byte[] {9}));
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(cold);
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);

            SessionReclaimReport report = service.reclaimOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.reclaimed()).isZero();
            assertThat(report.failures()).hasSize(1);
            assertThat(report.failures().get(0)).contains(sessionId).contains("REFUSED")
                    .contains("1 archived message(s)");
            // Asserted on the mapper rather than on the counters: a bug that deleted the rows and still
            // reported "refused" would satisfy the report alone.
            verify(archiveMapper, never()).deleteColdCopy(anyString());
        }

        @Test
        @DisplayName("a cold copy whose live transcript is empty is refused, not deleted")
        void refusesWhenTheLiveTranscriptIsEmpty() {
            // The shape a purged-but-uncertified session would have: if the certification ever vanished
            // while the hot rows were gone, this copy would be the only record of the consultation.
            String sessionId = "session-only-copy";
            List<ChatMessageDO> live = transcript(sessionId, 2);
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(List.of());

            SessionReclaimReport report = service.reclaimOnce();

            assertThat(report.refused()).isEqualTo(1);
            assertThat(report.reclaimedMessages()).isZero();
            assertThat(report.failures().get(0)).contains("2 archived message(s)");
            verify(archiveMapper, never()).deleteColdCopy(anyString());
        }

        @Test
        @DisplayName("a refused copy stays a candidate, so the backlog does not silently shrink")
        void refusedCopiesStayCandidates() {
            String sessionId = "session-stuck";
            List<ChatMessageDO> live = transcript(sessionId, 1);
            stubCandidate(sessionId);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(List.of());
            when(archiveMapper.countReclaimableOrphans(any())).thenReturn(1L);

            SessionReclaimReport report = service.reclaimOnce();

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
        List<ChatMessageDO> live = transcript(sessionId, 4);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
        when(archiveMapper.countReclaimableOrphans(any())).thenReturn(9L);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.reclaimed()).isEqualTo(1);
        assertThat(report.reclaimedMessages()).isEqualTo(4);
        assertThat(report.remaining()).isEqualTo(9);
        // The verification really happened - that is what makes the report worth reading...
        verify(archiveMapper).selectManifest(sessionId);
        verify(archiveMapper).selectBySessionId(sessionId);
        verify(messageMapper).selectTranscriptBySessionId(sessionId);
        // ...and nothing was written.
        verify(archiveMapper, never()).deleteColdCopy(anyString());
    }

    @Test
    @DisplayName("a dry run reports the refusals too, so arming the job is an informed decision")
    void dryRunReportsRefusals() {
        properties.setDryRun(true);
        String sessionId = "session-dry-refused";
        List<ChatMessageDO> live = transcript(sessionId, 1);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(List.of());

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.refused()).isEqualTo(1);
        verify(archiveMapper, never()).deleteColdCopy(anyString());
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
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(List.of());

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.batches()).isEqualTo(1);
        verify(archiveMapper, times(1)).selectReclaimableOrphans(any(), anyInt());
    }

    // ------------------------------------------------------------------ batching

    @Test
    @DisplayName("an empty candidate set completes without touching the archive")
    void emptyRunCompletes() {
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of());

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.COMPLETED);
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
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of(session(first, SessionStatus.ACTIVE), session(second, SessionStatus.ACTIVE)),
                        List.of(session(third, SessionStatus.CLOSED)));
        for (String sessionId : List.of(first, second, third)) {
            List<ChatMessageDO> live = transcript(sessionId, 1);
            when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
            when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
            when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
            when(archiveMapper.deleteColdCopy(sessionId)).thenReturn(1);
        }

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(3);
        assertThat(report.reclaimed()).isEqualTo(3);
        assertThat(report.reclaimedMessages()).isEqualTo(3);
    }

    @Test
    @DisplayName("max-batches bounds one run and the remaining backlog is reported")
    void maxBatchesBoundsTheRun() {
        properties.setBatchSize(1);
        properties.setMaxBatches(2);
        String sessionId = "session-bounded";
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(List.of());
        when(archiveMapper.countReclaimableOrphans(any())).thenReturn(11L);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.batches()).isEqualTo(2);
        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.skipped()).isEqualTo(2);
        assertThat(report.remaining()).isEqualTo(11);
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    @DisplayName("one failing candidate never aborts the run")
    void aFailingCandidateDoesNotAbortTheRun() {
        String broken = "session-broken";
        String healthy = "session-healthy";
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of(session(broken, SessionStatus.ACTIVE),
                        session(healthy, SessionStatus.ACTIVE)));
        when(archiveMapper.selectManifest(broken))
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));
        List<ChatMessageDO> live = transcript(healthy, 1);
        when(archiveMapper.selectManifest(healthy)).thenReturn(null);
        when(archiveMapper.selectBySessionId(healthy)).thenReturn(coldCopy(healthy, live));
        when(messageMapper.selectTranscriptBySessionId(healthy)).thenReturn(live);
        when(archiveMapper.deleteColdCopy(healthy)).thenReturn(1);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.reclaimed()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0)).contains(broken).contains("STORAGE_ERROR");
        verify(archiveMapper).deleteColdCopy(healthy);
    }

    @Test
    @DisplayName("a delete that fails is a failure, and the copy stays for the next run")
    void aFailingDeleteIsReported() {
        String sessionId = "session-no-delete";
        List<ChatMessageDO> live = transcript(sessionId, 1);
        stubCandidate(sessionId);
        when(archiveMapper.selectManifest(sessionId)).thenReturn(null);
        when(archiveMapper.selectBySessionId(sessionId)).thenReturn(coldCopy(sessionId, live));
        when(messageMapper.selectTranscriptBySessionId(sessionId)).thenReturn(live);
        when(archiveMapper.deleteColdCopy(sessionId))
                .thenThrow(new DataAccessResourceFailureException("mysql went away"));

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.reclaimed()).isZero();
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.failures().get(0)).contains("STORAGE_ERROR");
    }

    // ------------------------------------------------------------------ mutex

    @Test
    @DisplayName("a replica that does not get the mutex does not read a single row")
    void lockHeldElsewhereSkipsTheRun() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenReturn(false);

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.reclaimed()).isZero();
        assertThat(report.remaining()).isEqualTo(-1);
        verifyNoInteractions(archiveMapper, messageMapper, sessionMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("an unreachable Redis propagates instead of degrading to an unlocked reclaim")
    void unreachableRedisPropagates() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> service.reclaimOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("reclaim lock");
        verifyNoInteractions(archiveMapper, messageMapper, sessionMapper);
    }

    @Test
    @DisplayName("a zero lease switches the acquisition to the watchdog form")
    void zeroLeaseUsesTheWatchdogForm() throws InterruptedException {
        properties.setLockLeaseTime(Duration.ZERO);
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of());

        service.reclaimOnce();

        verify(lock).tryLock(anyLong(), any(java.util.concurrent.TimeUnit.class));
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));
    }

    @Test
    @DisplayName("an interrupted acquisition restores the interrupt flag and fails as INTERNAL_ERROR")
    void interruptedAcquisitionIsReported() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenThrow(new InterruptedException("shutdown"));

        assertThatThrownBy(() -> service.reclaimOnce())
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
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("redis went away")).when(lock).unlock();

        SessionReclaimReport report = service.reclaimOnce();

        assertThat(report.outcome()).isEqualTo(SessionReclaimReport.Outcome.COMPLETED);
        verify(lock).unlock();
    }

    // ------------------------------------------------------------------ constructor

    @Test
    @DisplayName("the constructor refuses a null collaborator")
    void constructorRejectsNulls() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MedSessionReclaimService(null, archiveMapper, messageMapper,
                lockService, redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionMapper");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, null, messageMapper,
                lockService, redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiveMapper");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, archiveMapper, null,
                lockService, redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messageMapper");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper,
                null, redissonClient, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lockService");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper,
                lockService, null, properties, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redissonClient");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper,
                lockService, redissonClient, null, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("properties");
        assertThatThrownBy(() -> new MedSessionReclaimService(sessionMapper, archiveMapper, messageMapper,
                lockService, redissonClient, properties, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clock");
    }

    // ------------------------------------------------------------------ fixtures

    private void stubCandidate(String sessionId) {
        when(archiveMapper.selectReclaimableOrphans(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of(session(sessionId, SessionStatus.ACTIVE)));
    }

    private static ChatSessionDO session(String sessionId, SessionStatus status) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId(PATIENT);
        session.setStatus(status);
        session.setCreatedAt(TOUCHED_AT);
        session.setUpdatedAt(TOUCHED_AT);
        return session;
    }

    /**
     * Builds a transcript with strictly increasing timestamps, mirroring the order the cold table and the
     * live shard are both read in.
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
                    .createdAt(TOUCHED_AT + index * 1_000L)
                    .metadata(new LinkedHashMap<>(Map.of("source", "web")))
                    .build());
        }
        return transcript;
    }

    /**
     * Builds the cold copy of a transcript. The payloads are placeholders: this job compares message
     * identity, not bytes (see the service's class javadoc for why), so only the ids have to be real.
     */
    private static List<ArchivedMessageDO> coldCopy(String sessionId, List<ChatMessageDO> transcript) {
        List<ArchivedMessageDO> rows = new ArrayList<>(transcript.size());
        for (ChatMessageDO message : transcript) {
            rows.add(new ArchivedMessageDO(sessionId, message.getMessageId(), TENANT, DEPT, PATIENT,
                    message.getCreatedAt(), TOUCHED_AT + 2_000L, new byte[] {1, 2, 3}));
        }
        return rows;
    }
}
