package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedSessionArchiveProperties;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 * Tests of {@link MedSessionArchiveExportService}, the job that gives "archived" a verifiable
 * counterpart in storage (D48).
 *
 * <p>Four behaviours carry the weight of this class, and each of them fails silently in production if
 * it regresses:</p>
 * <ol>
 *   <li><b>Nothing is written unless a run may write.</b> A replica that lost the mutex must not read a
 *       single row, and a dry run must not insert a single archive row or manifest.</li>
 *   <li><b>Certification requires evidence.</b> The manifest must not be written when the source moved
 *       during the copy, nor when the cold copy does not reproduce the digest. A manifest that exists is
 *       read by {@code verify} as a promise, so writing one without evidence would turn the archive into
 *       a liar.</li>
 *   <li><b>A refusal is not an exception.</b> Both refusals are counted in {@code mismatched} with their
 *       reason, so an operator can tell a moved source from a corrupt cold store.</li>
 *   <li><b>One broken session does not stop the run.</b> The remaining candidates are still exported.</li>
 * </ol>
 *
 * <p>The mappers are Mockito doubles; what the SQL really does (the {@code NOT EXISTS} candidate query,
 * the idempotent insert, the manifest compare-and-set) is verified against the real DDL through
 * ShardingSphere in {@code SessionArchiveMapperShardingTest}.</p>
 */
class MedSessionArchiveExportServiceTest {

    private static final String SESSION = "session-1";

    private static final String TENANT = "tenant-archive";

    private static final String DEPT = "dept-cardiology";

    private static final String PATIENT = "patient-1";

    private static final long NOW = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli();

    private final ProtoMessageCodec codec = new ProtoMessageCodec();

    private ChatMessageMapper messageMapper;

    private SessionArchiveMapper archiveMapper;

    private RedissonClient redissonClient;

    private RLock lock;

    private MedSessionArchiveProperties properties;

    private MedSessionArchiveExportService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        messageMapper = mock(ChatMessageMapper.class);
        archiveMapper = mock(SessionArchiveMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        properties = new MedSessionArchiveProperties();
        properties.setEnabled(true);
        properties.setDryRun(false);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        service = new MedSessionArchiveExportService(messageMapper, archiveMapper, codec,
                redissonClient, properties, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ the run itself

    @Test
    @DisplayName("an archived transcript is copied in order and certified with its digest")
    void exportsAnArchivedTranscript() {
        ChatMessageDO question = message(SESSION, "msg-1", 1_000L, "chest pain for two days",
                RoleType.PATIENT);
        ChatMessageDO answer = message(SESSION, "msg-2", 1_001L, "how long does it last?",
                RoleType.ASSISTANT);
        stubCandidates(archivedSession(SESSION));
        when(messageMapper.selectTranscriptBySessionId(SESSION))
                .thenReturn(List.of(question, answer));
        when(archiveMapper.selectBySessionId(SESSION))
                .thenReturn(List.of(archivedRow(question), archivedRow(answer)));
        when(archiveMapper.insertManifestIfAbsent(any())).thenReturn(1);
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(0L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.outcome()).isEqualTo(SessionArchiveExportReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.exported()).isEqualTo(1);
        assertThat(report.skipped()).isZero();
        assertThat(report.mismatched()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.batches()).isEqualTo(1);
        assertThat(report.remaining()).isZero();
        assertThat(report.failures()).isEmpty();

        ArgumentCaptor<ArchivedMessageDO> rows = ArgumentCaptor.forClass(ArchivedMessageDO.class);
        verify(archiveMapper, times(2)).insertIfAbsent(rows.capture());
        assertThat(rows.getAllValues()).extracting(ArchivedMessageDO::getMessageId)
                .containsExactly("msg-1", "msg-2");
        assertThat(rows.getAllValues()).extracting(ArchivedMessageDO::getTenantId)
                .containsOnly(TENANT);
        assertThat(rows.getAllValues()).extracting(ArchivedMessageDO::getDeptId).containsOnly(DEPT);
        assertThat(rows.getAllValues()).extracting(ArchivedMessageDO::getPatientId).containsOnly(PATIENT);
        assertThat(rows.getAllValues()).extracting(ArchivedMessageDO::getArchivedAt).containsOnly(NOW);
        assertThat(rows.getAllValues().get(0).getPayload()).isEqualTo(codec.encodeMessage(question));

        ArgumentCaptor<SessionArchiveManifestDO> manifest =
                ArgumentCaptor.forClass(SessionArchiveManifestDO.class);
        verify(archiveMapper).insertManifestIfAbsent(manifest.capture());
        assertThat(manifest.getValue().getSessionId()).isEqualTo(SESSION);
        assertThat(manifest.getValue().getMessageCount()).isEqualTo(2);
        assertThat(manifest.getValue().getPayloadChecksum())
                .isEqualTo(SessionArchiveChecksum.of(entries(question, answer)));
        assertThat(manifest.getValue().getExportedAt()).isEqualTo(NOW);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("an archived session that never received a message is certified with the empty digest")
    void certifiesAnEmptyTranscript() {
        // Not a degenerate case: the manifest is the only thing that takes a session out of the
        // candidate set, so refusing to certify an empty transcript would leave the job walking it
        // forever.
        stubCandidates(archivedSession(SESSION));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of());
        when(archiveMapper.insertManifestIfAbsent(any())).thenReturn(1);
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(0L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.exported()).isEqualTo(1);
        verify(archiveMapper, never()).insertIfAbsent(any());
        ArgumentCaptor<SessionArchiveManifestDO> manifest =
                ArgumentCaptor.forClass(SessionArchiveManifestDO.class);
        verify(archiveMapper).insertManifestIfAbsent(manifest.capture());
        assertThat(manifest.getValue().getMessageCount()).isZero();
        assertThat(manifest.getValue().getPayloadChecksum())
                .isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
    }

    @Test
    @DisplayName("a run with nothing to export completes without touching the message tables")
    void emptyRunCompletes() {
        when(archiveMapper.selectUnexportedArchivedSessions(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of());
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(0L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.outcome()).isEqualTo(SessionArchiveExportReport.Outcome.COMPLETED);
        assertThat(report.candidates()).isZero();
        assertThat(report.batches()).isZero();
        verifyNoInteractions(messageMapper);
        verify(lock).unlock();
    }

    // ------------------------------------------------------------------ the two switches

    @Test
    @DisplayName("a dry run scans one batch and writes nothing at all")
    void dryRunWritesNothing() {
        properties.setDryRun(true);
        stubCandidates(archivedSession(SESSION));
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(1L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.dryRun()).isTrue();
        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.exported()).isZero();
        assertThat(report.batches()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(1);
        verifyNoInteractions(messageMapper);
        verify(archiveMapper, never()).insertIfAbsent(any());
        verify(archiveMapper, never()).insertManifestIfAbsent(any());
        // A dry run changes nothing, so a second pass would fetch the very same sessions.
        verify(archiveMapper, times(1))
                .selectUnexportedArchivedSessions(eq(SessionStatus.ARCHIVED), anyInt());
    }

    @Test
    @DisplayName("a replica that does not get the mutex does not read a single row")
    void lockHeldStepsAsideWithoutTouchingTheDatabase() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.outcome()).isEqualTo(SessionArchiveExportReport.Outcome.SKIPPED_LOCK_HELD);
        assertThat(report.candidates()).isZero();
        assertThat(report.remaining()).isEqualTo(-1L);
        verifyNoInteractions(archiveMapper, messageMapper);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Redis unreachable while acquiring the mutex is a storage error, not an unlocked run")
    void redisUnreachableIsAStorageError() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisException("connection refused"));

        assertThatThrownBy(() -> service.exportOnce())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("archive export lock")
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STORAGE_ERROR));
        verifyNoInteractions(archiveMapper, messageMapper);
    }

    // ------------------------------------------------------------------ certification

    @Test
    @DisplayName("a transcript that moved while it was being copied is refused certification")
    void sourceChangedDuringTheCopyIsRefused() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        ChatMessageDO arrived = message(SESSION, "msg-2", 1_002L, "and a fever", RoleType.PATIENT);
        stubCandidates(archivedSession(SESSION));
        when(messageMapper.selectTranscriptBySessionId(SESSION))
                .thenReturn(List.of(first), List.of(first, arrived));
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(1L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.mismatched()).isEqualTo(1);
        assertThat(report.exported()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0))
                .contains(SESSION, "MISMATCH", "changed while it was being copied");
        verify(archiveMapper, never()).insertManifestIfAbsent(any());
        // The source check short-circuits: there is no point reading the cold copy of a transcript that
        // has already been shown to be moving.
        verify(archiveMapper, never()).selectBySessionId(anyString());
    }

    @Test
    @DisplayName("a cold copy that does not reproduce the source digest is refused certification")
    void coldCopyThatDoesNotReproduceTheDigestIsRefused() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        stubCandidates(archivedSession(SESSION));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first));
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of(tamperedRow(first)));
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(1L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.mismatched()).isEqualTo(1);
        assertThat(report.exported()).isZero();
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0))
                .contains(SESSION, "MISMATCH", "does not reproduce the source digest");
        verify(archiveMapper, never()).insertManifestIfAbsent(any());
    }

    @Test
    @DisplayName("a session already certified by another run is counted as skipped, not exported")
    void alreadyCertifiedSessionCountsAsSkipped() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        stubCandidates(archivedSession(SESSION));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first));
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of(archivedRow(first)));
        when(archiveMapper.insertManifestIfAbsent(any())).thenReturn(0);
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(0L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(report.exported()).isZero();
        assertThat(report.mismatched()).isZero();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    @DisplayName("one broken session does not abort the run: the rest is still exported")
    void oneBrokenSessionDoesNotAbortTheRun() {
        String broken = "session-broken";
        String healthy = "session-healthy";
        ChatMessageDO followUp = message(healthy, "msg-9", 2_000L, "follow up", RoleType.DOCTOR);
        stubCandidates(archivedSession(broken), archivedSession(healthy));
        when(messageMapper.selectTranscriptBySessionId(broken))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));
        when(messageMapper.selectTranscriptBySessionId(healthy)).thenReturn(List.of(followUp));
        when(archiveMapper.selectBySessionId(healthy)).thenReturn(List.of(archivedRow(followUp)));
        when(archiveMapper.insertManifestIfAbsent(any())).thenReturn(1);
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(1L);

        SessionArchiveExportReport report = service.exportOnce();

        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.exported()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.remaining()).isEqualTo(1);
        assertThat(report.failures()).hasSize(1);
        assertThat(report.failures().get(0))
                .as("a BizException is rendered with its error code, not with its class name")
                .contains(broken, "STORAGE_ERROR");
    }

    // ------------------------------------------------------------------ verification

    @Test
    @DisplayName("a session that was never exported verifies as MANIFEST_MISSING")
    void verificationReportsAMissingManifest() throws InterruptedException {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first));
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of());
        when(archiveMapper.selectManifest(SESSION)).thenReturn(null);

        SessionArchiveVerification verification = service.verify(SESSION);

        assertThat(verification.status())
                .isEqualTo(SessionArchiveVerification.Status.MANIFEST_MISSING);
        assertThat(verification.verified()).isFalse();
        assertThat(verification.hotMessageCount()).isEqualTo(1);
        assertThat(verification.archivedMessageCount()).isZero();
        assertThat(verification.manifestChecksum()).isEmpty();
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("a certified copy whose source is unchanged verifies as VERIFIED")
    void verificationConfirmsACertifiedCopy() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        ChatMessageDO second = message(SESSION, "msg-2", 1_001L, "for two days", RoleType.PATIENT);
        String digest = SessionArchiveChecksum.of(entries(first, second));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first, second));
        when(archiveMapper.selectBySessionId(SESSION))
                .thenReturn(List.of(archivedRow(first), archivedRow(second)));
        when(archiveMapper.selectManifest(SESSION)).thenReturn(manifest(SESSION, digest));

        SessionArchiveVerification verification = service.verify(SESSION);

        assertThat(verification.status()).isEqualTo(SessionArchiveVerification.Status.VERIFIED);
        assertThat(verification.verified()).isTrue();
        assertThat(verification.hotMessageCount()).isEqualTo(2);
        assertThat(verification.archivedMessageCount()).isEqualTo(2);
        assertThat(verification.hotChecksum()).isEqualTo(digest);
        assertThat(verification.manifestChecksum()).isEqualTo(digest);
    }

    @Test
    @DisplayName("a source that moved after the export verifies as TRANSCRIPT_CHANGED")
    void verificationDetectsAMovedSource() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        ChatMessageDO arrived = message(SESSION, "msg-2", 1_002L, "and a fever", RoleType.PATIENT);
        String certified = SessionArchiveChecksum.of(entries(first));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first, arrived));
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of(archivedRow(first)));
        when(archiveMapper.selectManifest(SESSION)).thenReturn(manifest(SESSION, certified));

        SessionArchiveVerification verification = service.verify(SESSION);

        assertThat(verification.status())
                .isEqualTo(SessionArchiveVerification.Status.TRANSCRIPT_CHANGED);
        assertThat(verification.hotChecksum()).isNotEqualTo(certified);
    }

    @Test
    @DisplayName("an archive row altered after certification verifies as COLD_COPY_DIFFERS")
    void verificationDetectsACorruptColdCopy() {
        ChatMessageDO first = message(SESSION, "msg-1", 1_000L, "chest pain", RoleType.PATIENT);
        String certified = SessionArchiveChecksum.of(entries(first));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first));
        when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of(tamperedRow(first)));
        when(archiveMapper.selectManifest(SESSION)).thenReturn(manifest(SESSION, certified));

        SessionArchiveVerification verification = service.verify(SESSION);

        assertThat(verification.status())
                .isEqualTo(SessionArchiveVerification.Status.COLD_COPY_DIFFERS);
        assertThat(verification.hotChecksum()).isEqualTo(certified);
        assertThat(verification.archivedChecksum()).isNotEqualTo(certified);
    }

    @Test
    @DisplayName("a blank session id is rejected instead of verifying some other session")
    void verificationRejectsABlankSessionId() {
        assertThatThrownBy(() -> service.verify(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId must not be blank");
        verifyNoInteractions(messageMapper, archiveMapper);
    }

    // ------------------------------------------------------------------ backlog and wiring

    @Test
    @DisplayName("the pending backlog is the mapper count, floored at zero")
    void pendingBacklogIsReported() {
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(7L);
        assertThat(service.countPending()).isEqualTo(7L);

        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED)).thenReturn(-3L);
        assertThat(service.countPending()).isZero();
    }

    @Test
    @DisplayName("a failing backlog count is a storage error")
    void failingBacklogCountIsAStorageError() {
        when(archiveMapper.countUnexportedArchivedSessions(SessionStatus.ARCHIVED))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.countPending())
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STORAGE_ERROR));
    }

    @Test
    @DisplayName("no collaborator may be null")
    void collaboratorsMustNotBeNull() {
        assertThatThrownBy(() -> new MedSessionArchiveExportService(null, archiveMapper, codec,
                redissonClient, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageMapper must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportService(messageMapper, null, codec,
                redissonClient, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiveMapper must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportService(messageMapper, archiveMapper, null,
                redissonClient, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codec must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportService(messageMapper, archiveMapper, codec,
                null, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redissonClient must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportService(messageMapper, archiveMapper, codec,
                redissonClient, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("properties must not be null");
        assertThatThrownBy(() -> new MedSessionArchiveExportService(messageMapper, archiveMapper, codec,
                redissonClient, properties, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock must not be null");
    }

    // ------------------------------------------------------------------ helpers

    private void stubCandidates(ChatSessionDO... sessions) {
        when(archiveMapper.selectUnexportedArchivedSessions(eq(SessionStatus.ARCHIVED), anyInt()))
                .thenReturn(List.of(sessions));
    }

    private static ChatSessionDO archivedSession(String sessionId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(sessionId);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId(PATIENT);
        session.setStatus(SessionStatus.ARCHIVED);
        session.setCreatedAt(1_000L);
        session.setUpdatedAt(1_000L);
        return session;
    }

    private static ChatMessageDO message(String sessionId, String messageId, long createdAt,
                                         String content, RoleType role) {
        return ChatMessageDO.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId(PATIENT)
                .role(role)
                .content(content)
                .createdAt(createdAt)
                .build();
    }

    private ArchivedMessageDO archivedRow(ChatMessageDO message) {
        return new ArchivedMessageDO(message.getSessionId(), message.getMessageId(), TENANT, DEPT,
                PATIENT, message.getCreatedAt(), NOW, codec.encodeMessage(message));
    }

    /** The cold copy as it would look if somebody had altered the stored bytes after the export. */
    private ArchivedMessageDO tamperedRow(ChatMessageDO message) {
        return new ArchivedMessageDO(message.getSessionId(), message.getMessageId(), TENANT, DEPT,
                PATIENT, message.getCreatedAt(), NOW, "tampered".getBytes(StandardCharsets.UTF_8));
    }

    private static SessionArchiveManifestDO manifest(String sessionId, String digest) {
        return new SessionArchiveManifestDO(sessionId, TENANT, DEPT, PATIENT, 1, digest, NOW);
    }

    private List<SessionArchiveChecksum.Entry> entries(ChatMessageDO... messages) {
        List<SessionArchiveChecksum.Entry> entries = new ArrayList<>(messages.length);
        for (ChatMessageDO message : messages) {
            entries.add(new SessionArchiveChecksum.Entry(message.getMessageId(), message.getCreatedAt(),
                    codec.encodeMessage(message)));
        }
        return entries;
    }
}
