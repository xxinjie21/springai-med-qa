package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.mapper.AuditLogMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Unit tests of the audit-trail review service.
 *
 * <p>The mapper is mocked and the properties are real, so the service is exercised in isolation:
 * filter pass-through, the two-statement count/page contract, the page-size bounds and the MySQL
 * failure shape. No middleware is contacted.</p>
 */
class AuditLogServiceTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "cardiology";

    private AuditLogMapper auditLogMapper;

    private MedAuditProperties properties;

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        auditLogMapper = mock(AuditLogMapper.class);
        properties = new MedAuditProperties();
        service = new AuditLogService(auditLogMapper, properties);
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

    private static AuditLogPageQuery query() {
        return AuditLogPageQuery.builder(TENANT, DEPT).build();
    }

    @Nested
    @DisplayName("a review of one department")
    class Review {

        @Test
        @DisplayName("forwards every filter and the time window to both statements")
        void forwardsFiltersAndWindow() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, "staff-42", "SESSION_CLOSE",
                    AuditOutcome.FAILURE, 1_000L, 2_000L)).thenReturn(7L);
            when(auditLogMapper.selectPage(TENANT, DEPT, "staff-42", "SESSION_CLOSE",
                    AuditOutcome.FAILURE, 1_000L, 2_000L, 0L, 20))
                    .thenReturn(List.of(entry("a-1", 1_500L)));

            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT)
                    .operatorId("staff-42")
                    .action("SESSION_CLOSE")
                    .outcome(AuditOutcome.FAILURE)
                    .createdFrom(1_000L)
                    .createdTo(2_000L)
                    .build();

            PageResult<AuditLogDO> result = service.review(query);

            assertThat(result.total()).isEqualTo(7L);
            assertThat(result.records()).hasSize(1);
            assertThat(result.records().get(0).getAuditId()).isEqualTo("a-1");
            assertThat(result.page()).isEqualTo(1);
            assertThat(result.size()).isEqualTo(20);
            verify(auditLogMapper).countByCondition(TENANT, DEPT, "staff-42", "SESSION_CLOSE",
                    AuditOutcome.FAILURE, 1_000L, 2_000L);
            verify(auditLogMapper).selectPage(TENANT, DEPT, "staff-42", "SESSION_CLOSE",
                    AuditOutcome.FAILURE, 1_000L, 2_000L, 0L, 20);
        }

        @Test
        @DisplayName("a page past the end returns an empty page carrying the real total")
        void pagePastEndIsEmptyWithRealTotal() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(3L);

            PageResult<AuditLogDO> result = service.review(
                    AuditLogPageQuery.builder(TENANT, DEPT).page(5).size(20).build());

            assertThat(result.records()).isEmpty();
            assertThat(result.total()).isEqualTo(3L);
            verify(auditLogMapper, never()).selectPage(any(), any(), any(), any(), any(), any(), any(),
                    anyLong(), anyInt());
        }

        @Test
        @DisplayName("an empty trail is a page with total zero, not an error")
        void emptyTrailIsAnEmptyPage() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(0L);

            PageResult<AuditLogDO> result = service.review(query());

            assertThat(result.total()).isZero();
            assertThat(result.records()).isEmpty();
        }

        @Test
        @DisplayName("a null result set from MySQL is rendered as an empty page")
        void nullRecordsBecomeEmptyPage() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(2L);
            when(auditLogMapper.selectPage(TENANT, DEPT, null, null, null, null, null, 0L, 20))
                    .thenReturn(null);

            PageResult<AuditLogDO> result = service.review(query());

            assertThat(result.records()).isEmpty();
            assertThat(result.total()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the offset follows the one-based page and the resolved size")
        void offsetFollowsPageAndSize() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(100L);
            when(auditLogMapper.selectPage(TENANT, DEPT, null, null, null, null, null, 40L, 20))
                    .thenReturn(List.of(entry("a-2", 1_200L)));

            service.review(AuditLogPageQuery.builder(TENANT, DEPT).page(3).build());

            verify(auditLogMapper).selectPage(TENANT, DEPT, null, null, null, null, null, 40L, 20);
        }
    }

    @Nested
    @DisplayName("page-size bounds")
    class PageSize {

        @Test
        @DisplayName("a null size applies the configured default")
        void nullSizeAppliesDefault() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(1L);
            when(auditLogMapper.selectPage(TENANT, DEPT, null, null, null, null, null, 0L, 20))
                    .thenReturn(List.of(entry("a-3", 1_300L)));

            PageResult<AuditLogDO> result = service.review(query());

            assertThat(result.size()).isEqualTo(properties.getDefaultPageSize());
            assertThat(service.resolvePageSize(null)).isEqualTo(20);
        }

        @Test
        @DisplayName("a size above the configured maximum is a 400")
        void rejectsOversizedPage() {
            assertThatThrownBy(() -> service.review(
                    AuditLogPageQuery.builder(TENANT, DEPT).size(101).build()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_REQUEST);
            verifyNoInteractions(auditLogMapper);
        }
    }

    @Nested
    @DisplayName("failure semantics")
    class Failures {

        @Test
        @DisplayName("a null query is a programming error")
        void rejectsNullQuery() {
            assertThatThrownBy(() -> service.review(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a failing count is a storage error")
        void countFailureIsStorageError() {
            when(auditLogMapper.countByCondition(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(mock(DataAccessException.class));

            assertThatThrownBy(() -> service.review(query()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }

        @Test
        @DisplayName("a failing page is a storage error")
        void selectFailureIsStorageError() {
            when(auditLogMapper.countByCondition(TENANT, DEPT, null, null, null, null, null))
                    .thenReturn(5L);
            when(auditLogMapper.selectPage(eq(TENANT), eq(DEPT), any(), any(), any(), any(), any(),
                    anyLong(), anyInt()))
                    .thenThrow(mock(DataAccessException.class));

            assertThatThrownBy(() -> service.review(query()))
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.STORAGE_ERROR);
        }
    }

    @Nested
    @DisplayName("constructor contract")
    class Construction {

        @Test
        @DisplayName("both collaborators are required")
        void requiresCollaborators() {
            assertThatThrownBy(() -> new AuditLogService(null, properties))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AuditLogService(auditLogMapper, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
