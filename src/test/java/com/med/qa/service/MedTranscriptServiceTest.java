package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.RoleType;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatMessageMapper;
import com.med.qa.mapper.SessionArchiveMapper;
import com.med.qa.memory.serde.ProtoMessageCodec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Tests of {@link MedTranscriptService}, the read path D49 added for archived consultations.
 *
 * <p>What carries the weight of this class is <em>which copy answers</em>. The choice is a digest
 * comparison rather than a flag or an ordering, and every branch fails differently in production:</p>
 * <ol>
 *   <li><b>A live session must be served from the shard</b>, without even reading the archive: an
 *       archived copy of an earlier state is not what the clinician asked for.</li>
 *   <li><b>An archived session with no manifest must still be readable.</b> That is the normal state of
 *       a deployment that never armed the archive job, and refusing it would make an optional
 *       background job a prerequisite for reading clinical records.</li>
 *   <li><b>A shard that no longer reproduces the certification must lose to the cold copy</b> — both
 *       when it was emptied (what D50 will do deliberately) and when it is only partially intact (what
 *       a half-finished purge leaves behind).</li>
 *   <li><b>Neither copy reproducing the certification must be a refusal</b>, never a best-effort
 *       answer: returning the shard would serve bytes the archive says are not the transcript, and
 *       returning an empty transcript would report data loss as "this consultation was empty".</li>
 * </ol>
 *
 * <p>The mappers and the lifecycle service are mocked; the Protobuf codec is real, because the cold
 * branch is only meaningful if the bytes really decode back into the storage-spec entity.</p>
 */
class MedTranscriptServiceTest {

    private static final String TENANT = "hosp-1";
    private static final String DEPT = "cardiology";
    private static final String PATIENT = "pat-77";
    private static final String SESSION = "sess-1";

    private MedChatSessionService sessionService;

    private ChatMessageMapper messageMapper;

    private SessionArchiveMapper archiveMapper;

    private ProtoMessageCodec codec;

    private MedTranscriptService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(MedChatSessionService.class);
        messageMapper = mock(ChatMessageMapper.class);
        archiveMapper = mock(SessionArchiveMapper.class);
        codec = new ProtoMessageCodec();
        service = new MedTranscriptService(sessionService, messageMapper, archiveMapper, codec);
    }

    @Nested
    @DisplayName("the live shard answers while it holds the transcript")
    class HotReads {

        @Test
        @DisplayName("an active session is served from the shard without touching the archive")
        void servesAnActiveSessionFromTheShard() {
            ChatMessageDO first = message("m-1", 1_000L, "chest pain for three days", RoleType.PATIENT);
            ChatMessageDO second = message("m-2", 2_000L, "any shortness of breath?", RoleType.DOCTOR);
            givenSession(SessionStatus.ACTIVE);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(first, second));

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.source()).isEqualTo(SessionTranscript.Source.HOT);
            assertThat(transcript.status()).isEqualTo(SessionStatus.ACTIVE);
            assertThat(transcript.messageCount()).isEqualTo(2);
            assertThat(transcript.messages()).extracting(ChatMessageDO::getMessageId)
                    .containsExactly("m-1", "m-2");
            assertThat(transcript.checksum())
                    .isEqualTo(TranscriptDigests.ofTranscript(List.of(first, second), codec));
            // A live transcript has no certification to consult: the archive must not even be queried.
            verifyNoInteractions(archiveMapper);
        }

        @Test
        @DisplayName("an archived session that was never exported still reads its shard")
        void servesAnUnexportedArchivedSessionFromTheShard() {
            ChatMessageDO only = message("m-1", 1_000L, "follow-up question", RoleType.PATIENT);
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(only));
            when(archiveMapper.selectManifest(SESSION)).thenReturn(null);

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.source()).isEqualTo(SessionTranscript.Source.HOT);
            assertThat(transcript.messageCount()).isEqualTo(1);
            verify(archiveMapper, never()).selectBySessionId(anyString());
        }

        @Test
        @DisplayName("an archived session whose shard still reproduces the certification stays on the shard")
        void prefersTheShardWhileItIsCertified() {
            List<ChatMessageDO> hot = List.of(
                    message("m-1", 1_000L, "question", RoleType.PATIENT),
                    message("m-2", 2_000L, "answer", RoleType.ASSISTANT));
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(hot);
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifestOf(hot));

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.source()).isEqualTo(SessionTranscript.Source.HOT);
            assertThat(transcript.checksum()).isEqualTo(manifestOf(hot).getPayloadChecksum());
            verify(archiveMapper, never()).selectBySessionId(anyString());
        }

        @Test
        @DisplayName("an empty consultation is a transcript, not an error")
        void servesAnEmptyTranscript() {
            givenSession(SessionStatus.ACTIVE);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.messageCount()).isZero();
            assertThat(transcript.checksum()).isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
        }
    }

    @Nested
    @DisplayName("the certified cold copy answers once the shard no longer does")
    class ColdReads {

        @Test
        @DisplayName("a purged shard is served from the cold copy, decoded back into storage entities")
        void fallsBackToTheColdCopyWhenTheShardIsEmpty() {
            List<ChatMessageDO> original = List.of(
                    message("m-1", 1_000L, "第一次问诊", RoleType.PATIENT),
                    message("m-2", 2_000L, "请描述症状", RoleType.DOCTOR));
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifestOf(original));
            when(archiveMapper.selectBySessionId(SESSION)).thenReturn(archivedRows(original));

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.fromColdStore()).isTrue();
            assertThat(transcript.messageCount()).isEqualTo(2);
            assertThat(transcript.checksum()).isEqualTo(manifestOf(original).getPayloadChecksum());
            assertThat(transcript.messages()).extracting(ChatMessageDO::getContent)
                    .containsExactly("第一次问诊", "请描述症状");
            assertThat(transcript.messages()).extracting(ChatMessageDO::getRole)
                    .containsExactly(RoleType.PATIENT, RoleType.DOCTOR);
        }

        @Test
        @DisplayName("a partially purged shard also loses to the cold copy")
        void fallsBackToTheColdCopyWhenTheShardIsIncomplete() {
            List<ChatMessageDO> original = List.of(
                    message("m-1", 1_000L, "question", RoleType.PATIENT),
                    message("m-2", 2_000L, "answer", RoleType.ASSISTANT));
            givenSession(SessionStatus.ARCHIVED);
            // Only the first message survived: the shard is non-empty but no longer the transcript.
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of(original.get(0)));
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifestOf(original));
            when(archiveMapper.selectBySessionId(SESSION)).thenReturn(archivedRows(original));

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.fromColdStore()).isTrue();
            assertThat(transcript.messages()).extracting(ChatMessageDO::getMessageId)
                    .containsExactly("m-1", "m-2");
        }

        @Test
        @DisplayName("an archived session with an empty certified transcript reads as an empty transcript")
        void servesAnEmptyCertifiedTranscript() {
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifestOf(List.of()));

            SessionTranscript transcript = service.read(TENANT, DEPT, SESSION);

            assertThat(transcript.source()).isEqualTo(SessionTranscript.Source.HOT);
            assertThat(transcript.messageCount()).isZero();
            assertThat(transcript.checksum()).isEqualTo(SessionArchiveChecksum.EMPTY_TRANSCRIPT);
        }
    }

    @Nested
    @DisplayName("an unaccountable transcript is refused, never guessed")
    class Refusals {

        @Test
        @DisplayName("neither copy reproducing the certification is a storage error")
        void refusesWhenNeitherCopyReproducesTheCertification() {
            List<ChatMessageDO> original = List.of(
                    message("m-1", 1_000L, "question", RoleType.PATIENT),
                    message("m-2", 2_000L, "answer", RoleType.ASSISTANT));
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION))
                    .thenReturn(List.of(message("m-1", 1_000L, "tampered", RoleType.PATIENT)));
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifestOf(original));
            when(archiveMapper.selectBySessionId(SESSION)).thenReturn(archivedRows(
                    List.of(message("m-2", 2_000L, "also tampered", RoleType.ASSISTANT))));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("a malformed cold payload is refused instead of being skipped")
        void refusesAMalformedColdPayload() {
            byte[] garbage = "not a protobuf message at all".getBytes(StandardCharsets.UTF_8);
            ArchivedMessageDO broken = new ArchivedMessageDO(SESSION, "m-1", TENANT, DEPT, PATIENT,
                    1_000L, 5_000L, garbage);
            // The manifest is computed over the stored bytes, so the digest check passes and the read
            // reaches the decoder - which is the branch under test.
            SessionArchiveManifestDO manifest = new SessionArchiveManifestDO(SESSION, TENANT, DEPT, PATIENT,
                    1, TranscriptDigests.ofArchived(List.of(broken)), 5_000L);
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
            when(archiveMapper.selectManifest(SESSION)).thenReturn(manifest);
            when(archiveMapper.selectBySessionId(SESSION)).thenReturn(List.of(broken));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("a storage failure of the shard read surfaces as a storage error")
        void wrapsAShardReadFailure() {
            givenSession(SessionStatus.ACTIVE);
            when(messageMapper.selectTranscriptBySessionId(SESSION))
                    .thenThrow(new DataAccessResourceFailureException("mysql down"));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("a storage failure of the manifest read surfaces as a storage error")
        void wrapsAManifestReadFailure() {
            givenSession(SessionStatus.ARCHIVED);
            when(messageMapper.selectTranscriptBySessionId(SESSION)).thenReturn(List.of());
            when(archiveMapper.selectManifest(SESSION))
                    .thenThrow(new DataAccessResourceFailureException("mysql down"));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }
    }

    @Nested
    @DisplayName("authorization is delegated, never re-implemented")
    class Authorization {

        @Test
        @DisplayName("a session outside the caller's department is reported as absent")
        void propagatesNotFound() {
            when(sessionService.getSession(TENANT, DEPT, SESSION))
                    .thenThrow(new BizException(ErrorCode.NOT_FOUND, "session does not exist"));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.NOT_FOUND);
            // Nothing is read before the session is resolved and authorized.
            verifyNoInteractions(messageMapper, archiveMapper);
        }

        @Test
        @DisplayName("a patient reading somebody else's consultation is refused by the lifecycle service")
        void propagatesForbidden() {
            when(sessionService.getSession(TENANT, DEPT, SESSION))
                    .thenThrow(new BizException(ErrorCode.FORBIDDEN, "not your session"));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, SESSION))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
            verifyNoInteractions(messageMapper, archiveMapper);
        }

        @Test
        @DisplayName("a blank session id is rejected before any storage access")
        void rejectsABlankSessionId() {
            when(sessionService.getSession(TENANT, DEPT, "  "))
                    .thenThrow(new IllegalArgumentException("sessionId must not be blank"));

            assertThatThrownBy(() -> service.read(TENANT, DEPT, "  "))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(messageMapper, archiveMapper);
        }
    }

    @Test
    @DisplayName("the service refuses to be built without its collaborators")
    void rejectsNullCollaborators() {
        assertThatThrownBy(() -> new MedTranscriptService(null, messageMapper, archiveMapper, codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MedTranscriptService(sessionService, null, archiveMapper, codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MedTranscriptService(sessionService, messageMapper, null, codec))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MedTranscriptService(sessionService, messageMapper, archiveMapper, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void givenSession(SessionStatus status) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId(SESSION);
        session.setTenantId(TENANT);
        session.setDeptId(DEPT);
        session.setPatientId(PATIENT);
        session.setStatus(status);
        session.setCreatedAt(500L);
        session.setUpdatedAt(1_000L);
        when(sessionService.getSession(TENANT, DEPT, SESSION)).thenReturn(session);
    }

    private static ChatMessageDO message(String messageId, long createdAt, String content, RoleType role) {
        return ChatMessageDO.builder()
                .messageId(messageId)
                .sessionId(SESSION)
                .tenantId(TENANT)
                .deptId(DEPT)
                .patientId(PATIENT)
                .role(role)
                .content(content)
                .createdAt(createdAt)
                .build();
    }

    private SessionArchiveManifestDO manifestOf(List<ChatMessageDO> transcript) {
        return new SessionArchiveManifestDO(SESSION, TENANT, DEPT, PATIENT, transcript.size(),
                TranscriptDigests.ofTranscript(transcript, codec), 5_000L);
    }

    private List<ArchivedMessageDO> archivedRows(List<ChatMessageDO> transcript) {
        return transcript.stream()
                .map(message -> new ArchivedMessageDO(SESSION, message.getMessageId(), TENANT, DEPT,
                        PATIENT, message.getCreatedAt(), 5_000L, codec.encodeMessage(message)))
                .toList();
    }
}
