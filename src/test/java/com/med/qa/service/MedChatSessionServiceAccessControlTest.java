package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedSessionProperties;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import com.med.qa.mapper.ChatSessionMapper;
import com.med.qa.memory.cache.RedisMessageCache;
import com.med.qa.memory.lock.SessionLockService;
import com.med.qa.security.MedPrincipal;
import com.med.qa.security.MedRole;
import com.med.qa.security.MedSecurityContext;
import com.med.qa.security.PatientAccessGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Integration tests of patient-ownership enforcement wired into {@link MedChatSessionService}.
 *
 * <p>The service is constructed with the real {@link PatientAccessGuard} and a {@link MedPrincipal}
 * bound to {@link MedSecurityContext}, exactly as the running application does once
 * {@code ApiKeyAuthFilter} has authenticated a request. The storage collaborators are mocked, so the
 * tests isolate the authorization decisions from MySQL.</p>
 */
class MedChatSessionServiceAccessControlTest {

    private ChatSessionMapper sessionMapper;

    private SessionLockService lockService;

    private RedisMessageCache cache;

    private MedSessionProperties properties;

    private PatientAccessGuard guard;

    private MedChatSessionService service;

    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        lockService = mock(SessionLockService.class);
        cache = mock(RedisMessageCache.class);
        properties = new MedSessionProperties();
        guard = new PatientAccessGuard();
        service = new MedChatSessionService(sessionMapper, lockService, cache, properties, guard, FIXED);

        // The lock simply runs the guarded action; the access-control path does not depend on Redis.
        when(lockService.executeLocked(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
    }

    @AfterEach
    void tearDown() {
        MedSecurityContext.clear();
    }

    private static ChatSessionDO session(String tenant, String dept, String patientId) {
        ChatSessionDO session = new ChatSessionDO();
        session.setSessionId("s-1");
        session.setTenantId(tenant);
        session.setDeptId(dept);
        session.setPatientId(patientId);
        session.setTitle("t");
        session.setStatus(SessionStatus.ACTIVE);
        session.setCreatedAt(1_700_000_000_000L);
        session.setUpdatedAt(1_700_000_000_000L);
        return session;
    }

    private static MedPrincipal patient(String patientId) {
        return new MedPrincipal("hosp-1", "cardiology", MedRole.PATIENT, patientId);
    }

    private static MedPrincipal staff() {
        return new MedPrincipal("hosp-1", "cardiology", MedRole.STAFF, null);
    }

    @Nested
    @DisplayName("session retrieval ownership")
    class Retrieval {

        @Test
        @DisplayName("a patient may load their own session")
        void patientOwns() {
            MedSecurityContext.setPrincipal(patient("pat-77"));
            when(sessionMapper.selectById("s-1")).thenReturn(session("hosp-1", "cardiology", "pat-77"));

            ChatSessionDO result = service.getSession("hosp-1", "cardiology", "s-1");

            assertThat(result.getPatientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("a patient is forbidden from another patient's session")
        void patientOtherForbidden() {
            MedSecurityContext.setPrincipal(patient("pat-99"));
            when(sessionMapper.selectById("s-1")).thenReturn(session("hosp-1", "cardiology", "pat-77"));

            assertThatThrownBy(() -> service.getSession("hosp-1", "cardiology", "s-1"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("staff may load any session of their department")
        void staffAny() {
            MedSecurityContext.setPrincipal(staff());
            when(sessionMapper.selectById("s-1")).thenReturn(session("hosp-1", "cardiology", "pat-77"));

            ChatSessionDO result = service.getSession("hosp-1", "cardiology", "s-1");

            assertThat(result.getPatientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("an unauthenticated caller is forbidden from loading a session")
        void noPrincipalForbidden() {
            MedSecurityContext.clear();
            when(sessionMapper.selectById("s-1")).thenReturn(session("hosp-1", "cardiology", "pat-77"));

            assertThatThrownBy(() -> service.getSession("hosp-1", "cardiology", "s-1"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("session creation scope")
    class Creation {

        @Test
        @DisplayName("a patient may create a session for their own patient id")
        void patientOwnScope() {
            MedSecurityContext.setPrincipal(patient("pat-77"));
            when(sessionMapper.insert(any(ChatSessionDO.class))).thenReturn(1);

            ChatSessionDO result = service.createSession("hosp-1", "cardiology", "pat-77", "chest pain");

            assertThat(result.getPatientId()).isEqualTo("pat-77");
        }

        @Test
        @DisplayName("a patient cannot create a session for another patient")
        void patientOtherScopeForbidden() {
            MedSecurityContext.setPrincipal(patient("pat-77"));
            when(sessionMapper.insert(any(ChatSessionDO.class))).thenReturn(1);

            assertThatThrownBy(() -> service.createSession("hosp-1", "cardiology", "pat-99", "x"))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("session listing scope")
    class Listing {

        @Test
        @DisplayName("a patient may list their own sessions")
        void patientOwnScope() {
            MedSecurityContext.setPrincipal(patient("pat-77"));
            when(sessionMapper.countByCondition(anyString(), anyString(), anyString(), any()))
                    .thenReturn(1L);
            when(sessionMapper.selectPage(anyString(), anyString(), anyString(), any(), anyLong(), anyInt()))
                    .thenReturn(List.of(session("hosp-1", "cardiology", "pat-77")));

            PageResult<ChatSessionDO> result = service.pageSessions(
                    SessionPageQuery.builder("hosp-1", "cardiology")
                            .patientId("pat-77").page(1).size(20).build());

            assertThat(result.total()).isEqualTo(1);
        }

        @Test
        @DisplayName("a patient cannot list another patient's sessions")
        void forbiddenOtherScope() {
            MedSecurityContext.setPrincipal(patient("pat-77"));

            assertThatThrownBy(() -> service.pageSessions(
                    SessionPageQuery.builder("hosp-1", "cardiology")
                            .patientId("pat-99").page(1).size(20).build()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("backward compatibility")
    class NoGuard {

        @Test
        @DisplayName("a guard-less service enforces nothing, even with a principal bound")
        void guardLessEnforcesNothing() {
            MedChatSessionService plain = new MedChatSessionService(
                    sessionMapper, lockService, cache, properties, FIXED);
            MedSecurityContext.setPrincipal(patient("pat-77"));
            when(sessionMapper.insert(any(ChatSessionDO.class))).thenReturn(1);

            ChatSessionDO result = plain.createSession("hosp-1", "cardiology", "pat-99", "x");

            assertThat(result).isNotNull();
        }
    }
}
