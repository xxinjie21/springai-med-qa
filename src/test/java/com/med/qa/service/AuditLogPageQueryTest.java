package com.med.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.med.qa.domain.enums.AuditOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the immutable audit-review query object.
 *
 * <p>The query is the only place where the mandatory tenant/department scope, the optional filters
 * and the half-inclusive time window are validated, so these tests pin the boundaries a malformed
 * review request must not be able to cross.</p>
 */
class AuditLogPageQueryTest {

    private static final String TENANT = "hosp-1";

    private static final String DEPT = "cardiology";

    @Nested
    @DisplayName("mandatory scope")
    class Scope {

        @Test
        @DisplayName("a tenant/department pair is required")
        void requiresScope() {
            assertThatThrownBy(() -> AuditLogPageQuery.builder(null, DEPT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenantId");
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deptId");
        }

        @Test
        @DisplayName("the defaults are page 1, no filters and no configured size")
        void defaultsToWholeDepartmentFirstPage() {
            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT).build();

            assertThat(query.tenantId()).isEqualTo(TENANT);
            assertThat(query.deptId()).isEqualTo(DEPT);
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
    @DisplayName("optional filters")
    class Filters {

        @Test
        @DisplayName("operator, action and outcome narrow the review")
        void narrowsByOperatorActionAndOutcome() {
            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT)
                    .operatorId("staff-42")
                    .action("SESSION_CLOSE")
                    .outcome(AuditOutcome.FAILURE)
                    .build();

            assertThat(query.operatorId()).isEqualTo("staff-42");
            assertThat(query.action()).isEqualTo("SESSION_CLOSE");
            assertThat(query.outcome()).isEqualTo(AuditOutcome.FAILURE);
        }

        @Test
        @DisplayName("a blank operator or action means no filter, not an empty result")
        void blankFilterMeansUnfiltered() {
            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT)
                    .operatorId(" ")
                    .action("")
                    .build();

            assertThat(query.operatorId()).isNull();
            assertThat(query.action()).isNull();
        }
    }

    @Nested
    @DisplayName("time window")
    class TimeWindow {

        @Test
        @DisplayName("a half-inclusive window [from, to) is accepted")
        void acceptsHalfInclusiveWindow() {
            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT)
                    .createdFrom(1_700_000_000_000L)
                    .createdTo(1_700_000_001_000L)
                    .build();

            assertThat(query.createdFrom()).isEqualTo(1_700_000_000_000L);
            assertThat(query.createdTo()).isEqualTo(1_700_000_001_000L);
        }

        @Test
        @DisplayName("an inverted window is rejected instead of silently returning nothing")
        void rejectsInvertedWindow() {
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, DEPT)
                    .createdFrom(1_700_000_001_000L)
                    .createdTo(1_700_000_000_000L)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("createdFrom");
        }

        @Test
        @DisplayName("a negative bound is rejected")
        void rejectsNegativeBound() {
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, DEPT).createdFrom(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("createdFrom");
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, DEPT).createdTo(-1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("createdTo");
        }
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("a non-positive page or size is rejected")
        void rejectsNonPositivePaging() {
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, DEPT).page(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("page");
            assertThatThrownBy(() -> AuditLogPageQuery.builder(TENANT, DEPT).size(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("size");
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("equal queries are equal and hash alike")
        void equalsAndHashCode() {
            AuditLogPageQuery left = AuditLogPageQuery.builder(TENANT, DEPT)
                    .operatorId("staff-42").page(2).size(10).build();
            AuditLogPageQuery right = AuditLogPageQuery.builder(TENANT, DEPT)
                    .operatorId("staff-42").page(2).size(10).build();

            assertThat(left).isEqualTo(right);
            assertThat(left.hashCode()).isEqualTo(right.hashCode());
            assertThat(left).isNotEqualTo(AuditLogPageQuery.builder(TENANT, DEPT).build());
        }

        @Test
        @DisplayName("toString carries the filters an operator needs in a log line")
        void toStringCarriesFilters() {
            AuditLogPageQuery query = AuditLogPageQuery.builder(TENANT, DEPT)
                    .outcome(AuditOutcome.SUCCESS).build();

            assertThat(query.toString())
                    .contains(TENANT)
                    .contains(DEPT)
                    .contains("SUCCESS");
        }
    }
}
