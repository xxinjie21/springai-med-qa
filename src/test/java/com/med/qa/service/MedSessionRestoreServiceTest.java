package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.memory.serde.ProtoMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests of {@link MedSessionRestoreService}, the one code path that moves a session out of
 * {@link SessionStatus#ARCHIVED}.
 *
 * <p>Four behaviours carry the weight of this class and are pinned here:</p>
 * <ol>
 *   <li><b>Nothing is moved back unless the live copy is still the certified transcript.</b> Both
 *       refusals — a retired live copy and a drifted one — are asserted on the mappers rather than on
 *       the exception alone: a bug could throw after writing.</li>
 *   <li><b>The certification is retired before the status is flipped.</b> Asserted with an
 *       {@link InOrder}, because the reverse order leaves an {@code ACTIVE} session whose manifest the
 *       export can never refresh (its candidate predicate is {@code NOT EXISTS (manifest)}).</li>
 *   <li><b>Authorization is not re-implemented.</b> The session is resolved through the lifecycle
 *       service, so a {@code FORBIDDEN} or {@code NOT_FOUND} from it propagates untouched and no row is
 *       touched.</li>
 *   <li><b>The transition is a compare-and-set on {@code ARCHIVED}.</b> Losing it is
 *       {@link ErrorCode#SESSION_LOCKED}, not a silent success.</li>
 * </ol>
 */
class MedSessionRestoreServiceTest {

    private static final String TENANT = "tenant-restore";

    private static final String DEPT = "dept-cardiology";

    private static final String PATIENT = "patient-1";

    private static final String SESSION = "session-restore";

    private static final long NOW = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli();

    private static final long ARCHIVED_AT = NOW - 2 * 24 * 3_600_000L;

    private final ProtoMessageCodec codec = new ProtoMessageCodec();

    private MedChatSessionService sessionService;

    private SessionLockService lockService;

    private ChatSessionMapper sessionMapper;

    private ChatMessageMapper messageMapper;

    private SessionArchiveMapper archiveMapper;

    private MedSessionRestoreService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(MedChatSessionService.class);
        lockService = mock(SessionLockService.class);
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        archiveMapper = mock(SessionArchiveMapper.class);

        // The session lock runs its action inline: the transition itself is what the tests assert, and
        // the lock's own behaviour is pinned in MedStorageAndLockIntegrationTest against real Redis.
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(lockService).executeLocked(anyString(), anyString(), anyString(), any());

        service = new MedSessionRestoreService(sessionService, lockService, sessionMapper, messageMapper,
                archiveMapper, codec, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("an archived session whose live shard reproduces its certification is moved back")
    void restoresACertifiedSession() {
        List<ChatMessageDO> transcript = transcript(SESSION, 3);
        String certified = TranscriptDigests.ofTranscript(transcript, codec);
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION)).thenReturn(manifest(certified));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(transcript);
        when(archiveMapper.deleteManifest(SESSION)).thenReturn(1);
        when(sessionMapper.updateStatus(eq(SESSION), eq(SessionStatus.ACTIVE),
                eq(SessionStatus.ARCHIVED), eq(NOW))).thenReturn(1);

        ChatSessionDO restored = service.restore(TENANT, DEPT, SESSION);

        assertThat(restored.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(restored.getUpdatedAt()).isEqualTo(NOW);
        verify(archiveMapper).deleteManifest(SESSION);
        verify(sessionMapper).updateStatus(SESSION, SessionStatus.ACTIVE, SessionStatus.ARCHIVED, NOW);
        // Restoring must not touch the transcript: re-materialising the hot tier would make archiving
        // physically reversible and empty the cooling-off window of its meaning.
        verify(messageMapper, never()).insertIfAbsent(any());
        verify(messageMapper, never()).deleteBySessionId(anyString());
    }

    @Test
    @DisplayName("a session that was never exported is moved back without touching the cold tables")
    void restoresASessionThatWasNeverExported() {
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION)).thenReturn(null);
        when(sessionMapper.updateStatus(eq(SESSION), eq(SessionStatus.ACTIVE),
                eq(SessionStatus.ARCHIVED), eq(NOW))).thenReturn(1);

        ChatSessionDO restored = service.restore(TENANT, DEPT, SESSION);

        assertThat(restored.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        verify(archiveMapper, never()).deleteManifest(anyString());
        // There is no certification to compare against, so the shard is not even read.
        verify(messageMapper, never()).selectTranscriptBySessionId(anyString());
    }

    @Test
    @DisplayName("the certification is retired before the status is flipped")
    void retiresTheCertificationBeforeFlippingTheStatus() {
        List<ChatMessageDO> transcript = transcript(SESSION, 1);
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION))
                .thenReturn(manifest(TranscriptDigests.ofTranscript(transcript, codec)));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(transcript);
        when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(1);

        service.restore(TENANT, DEPT, SESSION);

        // An interruption in between must leave "ARCHIVED with no manifest", which the next export
        // re-certifies. The reverse order would leave an ACTIVE session carrying a stale manifest that
        // the export can never refresh.
        InOrder inOrder = inOrder(archiveMapper, sessionMapper);
        inOrder.verify(archiveMapper).deleteManifest(SESSION);
        inOrder.verify(sessionMapper).updateStatus(SESSION, SessionStatus.ACTIVE, SessionStatus.ARCHIVED,
                NOW);
    }

    @Test
    @DisplayName("the transition compares-and-sets on ARCHIVED, never setting an arbitrary status")
    void transitionsWithACompareAndSetOnArchived() {
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION)).thenReturn(null);
        when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(1);

        service.restore(TENANT, DEPT, SESSION);

        ArgumentCaptor<SessionStatus> expected = ArgumentCaptor.forClass(SessionStatus.class);
        ArgumentCaptor<SessionStatus> target = ArgumentCaptor.forClass(SessionStatus.class);
        verify(sessionMapper).updateStatus(eq(SESSION), target.capture(), expected.capture(), eq(NOW));
        assertThat(expected.getValue()).isEqualTo(SessionStatus.ARCHIVED);
        assertThat(target.getValue()).isEqualTo(SessionStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ refusals

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a session that is not archived is refused and nothing is written")
        void refusesASessionThatIsNotArchived() {
            ChatSessionDO active = session(SESSION);
            active.setStatus(SessionStatus.ACTIVE);
            when(sessionService.getSession(TENANT, DEPT, SESSION)).thenReturn(active);

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("only an archived session")
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));

            verifyNoInteractions(archiveMapper);
            verifyNoInteractions(messageMapper);
            verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("a session whose live rows were retired is refused, and the reason is named")
        void refusesASessionWhoseRowsWereRetired() {
            ChatSessionDO purged = session(SESSION);
            purged.setPurgedAt(NOW - 1_000L);
            when(sessionService.getSession(TENANT, DEPT, SESSION)).thenReturn(purged);

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("retired")
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));

            verifyNoInteractions(archiveMapper);
            verifyNoInteractions(messageMapper);
            verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("a live shard that does not reproduce the certification is refused, and kept")
        void refusesADriftedLiveShard() {
            List<ChatMessageDO> transcript = transcript(SESSION, 2);
            stubArchivedSession();
            when(archiveMapper.selectManifest(SESSION))
                    .thenReturn(manifest(SessionArchiveChecksum.sha256Hex(
                            "unrelated".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(transcript);

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("does not reproduce the certified digest")
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.BAD_REQUEST));

            // The refusal must not retire the certification either: the claim is still the only thing
            // that describes the frozen copy, and the purge needs it.
            verify(archiveMapper, never()).deleteManifest(anyString());
            verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("a session that left ARCHIVED underneath the call is reported as busy")
        void reportsAConcurrentStatusChangeAsBusy() {
            stubArchivedSession();
            when(archiveMapper.selectManifest(SESSION)).thenReturn(null);
            when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(0);

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("left ARCHIVED concurrently")
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.SESSION_LOCKED));
        }

        @Test
        @DisplayName("an out-of-scope session propagates the lifecycle service's refusal untouched")
        void propagatesTheAuthorizationRefusal() {
            when(sessionService.getSession(TENANT, DEPT, SESSION))
                    .thenThrow(new BizException(ErrorCode.FORBIDDEN, "not yours"));

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("not yours");

            verifyNoInteractions(archiveMapper);
            verifyNoInteractions(messageMapper);
            verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("a busy session lock propagates as SESSION_LOCKED, not as a success")
        void propagatesABusyLock() {
            doThrow(new BizException(ErrorCode.SESSION_LOCKED, "busy"))
                    .when(lockService).executeLocked(anyString(), anyString(), anyString(), any());

            assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.SESSION_LOCKED));

            verifyNoInteractions(sessionService);
            verifyNoInteractions(sessionMapper);
        }
    }

    // ------------------------------------------------------------------ failure and boundary handling

    @Test
    @DisplayName("a storage failure while retiring the certification surfaces as STORAGE_ERROR")
    void retirementFailureSurfacesAsStorageError() {
        List<ChatMessageDO> transcript = transcript(SESSION, 1);
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION))
                .thenReturn(manifest(TranscriptDigests.ofTranscript(transcript, codec)));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(transcript);
        when(archiveMapper.deleteManifest(SESSION))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        assertThatThrownBy(() -> service.restore(TENANT, DEPT, SESSION))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STORAGE_ERROR));
        verify(sessionMapper, never()).updateStatus(anyString(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("an empty transcript reproduces the empty digest, so it is still restorable")
    void restoresAnEmptyCertifiedTranscript() {
        stubArchivedSession();
        when(archiveMapper.selectManifest(SESSION))
                .thenReturn(manifest(SessionArchiveChecksum.EMPTY_TRANSCRIPT));
        when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
        when(sessionMapper.updateStatus(anyString(), any(), any(), anyLong())).thenReturn(1);

        ChatSessionDO restored = service.restore(TENANT, DEPT, SESSION);

        assertThat(restored.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        verify(archiveMapper).deleteManifest(SESSION);
    }

    @Test
    @DisplayName("a blank identity segment is rejected before the lock is taken")
    void blankIdentityIsRejected() {
        assertThatThrownBy(() -> service.restore(" ", DEPT, SESSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> service.restore(TENANT, null, SESSION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deptId");
        assertThatThrownBy(() -> service.restore(TENANT, DEPT, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sessionId");
        verifyNoInteractions(lockService);
    }

    @Test
    @DisplayName("no collaborator may be null")
    void collaboratorsMustNotBeNull() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new MedSessionRestoreService(null, lockService, sessionMapper,
                messageMapper, archiveMapper, codec, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionService must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, null, sessionMapper,
                messageMapper, archiveMapper, codec, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockService must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, lockService, null,
                messageMapper, archiveMapper, codec, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionMapper must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, lockService, sessionMapper,
                null, archiveMapper, codec, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageMapper must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, lockService, sessionMapper,
                messageMapper, null, codec, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiveMapper must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, lockService, sessionMapper,
                messageMapper, archiveMapper, null, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codec must not be null");
        assertThatThrownBy(() -> new MedSessionRestoreService(sessionService, lockService, sessionMapper,
                messageMapper, archiveMapper, codec, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clock must not be null");
    }

    // ------------------------------------------------------------------ fixtures

    private void stubArchivedSession() {
        when(sessionService.getSession(TENANT, DEPT, SESSION)).thenReturn(session(SESSION));
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

    private static SessionArchiveManifestDO manifest(String checksum) {
        return new SessionArchiveManifestDO(SESSION, TENANT, DEPT, PATIENT, 1, checksum,
                ARCHIVED_AT + 1_000L);
    }

    /**
     * Builds a transcript with strictly increasing timestamps.
     *
     * <p>Explicit rather than derived from a clock: the canonical digest covers the <em>sequence</em>,
     * so arbitrary timestamps would be certified in one order and compared in another.</p>
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
}
