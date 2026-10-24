package com.med.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.audit.annotation.MedAudit;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.ApiResult;
import com.med.qa.common.result.PageResult;
import com.med.qa.controller.dto.AuditLogResponse;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.security.MedRole;
import com.med.qa.security.annotation.RequireDept;
import com.med.qa.service.AuditLogPageQuery;
import com.med.qa.service.AuditLogService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests of the audit-review REST controller.
 *
 * <p>The {@link AuditLogService} is mocked, so the controller is exercised in isolation: scope
 * validation, filter assembly, response shaping and the declarative authorization wiring. No
 * middleware is contacted.</p>
 */
class AuditLogControllerTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "cardiology";

    private AuditLogService auditLogService;

    private AuditLogController controller;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        controller = new AuditLogController(auditLogService);
    }

    private static AuditLogDO entry(String id, long createdAt) {
        AuditLogDO row = new AuditLogDO();
        row.setAuditId(id);
        row.setTenantId(TENANT);
        row.setDeptId(DEPT);
        row.setOperatorId("staff-42");
        row.setAction("SESSION_CLOSE");
        row.setOutcome(AuditOutcome.SUCCESS);
        row.setLatencyMillis(12L);
        row.setCreatedAt(createdAt);
        return row;
    }

    @Nested
    @DisplayName("a review request")
    class Listing {

        @Test
        @DisplayName("returns the page mapped to response DTOs")
        void listsEntries() {
            when(auditLogService.review(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(PageResult.of(1, 20, 42L,
                            List.of(entry("a-1", 1_700_000_000_000L))));

            ApiResult<PageResult<AuditLogResponse>> result = controller.list(
                    TENANT, DEPT, null, null, null, null, null, 1, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().total()).isEqualTo(42L);
            assertThat(result.getData().records()).hasSize(1);
            AuditLogResponse dto = result.getData().records().get(0);
            assertThat(dto.auditId()).isEqualTo("a-1");
            assertThat(dto.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(dto.createdAt()).isEqualTo(1_700_000_000_000L);
        }

        @Test
        @DisplayName("forwards every filter and the time window into the query object")
        void forwardsFilters() {
            when(auditLogService.review(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(PageResult.of(1, 20, 0L, List.of()));

            controller.list(TENANT, DEPT, "staff-42", "SESSION_CLOSE", AuditOutcome.FAILURE,
                    1_000L, 2_000L, 2, 10);

            ArgumentCaptor<AuditLogPageQuery> captor = forClass(AuditLogPageQuery.class);
            verify(auditLogService).review(captor.capture());
            AuditLogPageQuery query = captor.getValue();
            assertThat(query.tenantId()).isEqualTo(TENANT);
            assertThat(query.deptId()).isEqualTo(DEPT);
            assertThat(query.operatorId()).isEqualTo("staff-42");
            assertThat(query.action()).isEqualTo("SESSION_CLOSE");
            assertThat(query.outcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(query.createdFrom()).isEqualTo(1_000L);
            assertThat(query.createdTo()).isEqualTo(2_000L);
            assertThat(query.page()).isEqualTo(2);
            assertThat(query.size()).isEqualTo(10);
        }

        @Test
        @DisplayName("an omitted filter set reaches the service as nulls with page 1")
        void defaultsToWholeDepartmentFirstPage() {
            when(auditLogService.review(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(PageResult.of(1, 20, 0L, List.of()));

            controller.list(TENANT, DEPT, null, null, null, null, null, 1, null);

            ArgumentCaptor<AuditLogPageQuery> captor = forClass(AuditLogPageQuery.class);
            verify(auditLogService).review(captor.capture());
            AuditLogPageQuery query = captor.getValue();
            assertThat(query.operatorId()).isNull();
            assertThat(query.action()).isNull();
            assertThat(query.outcome()).isNull();
            assertThat(query.createdFrom()).isNull();
            assertThat(query.createdTo()).isNull();
            assertThat(query.page()).isEqualTo(1);
            assertThat(query.size()).isNull();
        }
    }

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        @DisplayName("a blank scope is rejected before the service runs")
        void rejectsBlankScope() {
            assertThatThrownBy(() -> controller.list(" ", DEPT, null, null, null, null, null, 1, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            assertThatThrownBy(() -> controller.list(TENANT, null, null, null, null, null, null, 1, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("a non-positive page is rejected")
        void rejectsNonPositivePage() {
            assertThatThrownBy(() -> controller.list(TENANT, DEPT, null, null, null, null, null, 0, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }

        @Test
        @DisplayName("an inverted time window is rejected instead of returning nothing")
        void rejectsInvertedWindow() {
            assertThatThrownBy(() -> controller.list(TENANT, DEPT, null, null, null,
                    2_000L, 1_000L, 1, null))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("authorization wiring")
    class Authorization {

        @Test
        @DisplayName("the endpoint is staff-only and itself audited")
        void isStaffOnlyAndAudited() throws NoSuchMethodException {
            Method list = AuditLogController.class.getMethod("list",
                    String.class, String.class, String.class, String.class, AuditOutcome.class,
                    Long.class, Long.class, int.class, Integer.class);

            RequireDept requireDept = list.getAnnotation(RequireDept.class);
            assertThat(requireDept).isNotNull();
            assertThat(requireDept.roles()).containsExactly(MedRole.STAFF);
            assertThat(list.getAnnotation(MedAudit.class)).isNotNull();
        }
    }
}
