package com.med.qa.mapper;

import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * MyBatis data-access mapper for the medical operation audit trail, backed by the single
 * (non-sharded) {@code med_audit_log} table.
 *
 * <p>The trail is append-only by design: it exposes an insert and read paths, but no update and no
 * delete. An audit row that can be rewritten from the application is not evidence, and retention
 * trimming belongs to a database-level job with its own authorization, not to the request path that
 * produced the entry. The two retention statements below are that job's only data access: they are
 * deliberately the <em>only</em> delete this interface exposes, they take an age cutoff rather than
 * any tenant/operator coordinate, and they exist so that no request-path caller ever has to build
 * one (see {@code MedAuditLogRetentionService}).</p>
 *
 * <p>Like {@code med_session}, the table stays unsharded: audit reviews filter by
 * tenant/department/operator/action over a time window, which is an index-backed range scan on one
 * table rather than a 16-way scatter-gather. ShardingSphere-JDBC routes it through its
 * {@code SINGLE} rule.</p>
 */
@Mapper
public interface AuditLogMapper {

    /**
     * Appends one audit entry.
     *
     * @param auditLog the entry to persist; must carry a non-blank {@code auditId}, {@code tenantId},
     *                 {@code deptId}, {@code operatorId}, {@code action} and a non-null outcome
     * @return the number of affected rows (1 on success)
     */
    int insert(AuditLogDO auditLog);

    /**
     * Loads one audit entry by its primary key.
     *
     * @param auditId the audit entry primary key
     * @return the matching entry, or {@code null} when absent
     */
    AuditLogDO selectById(@Param("auditId") String auditId);

    /**
     * Lists one page of audit entries of a tenant/department, newest first, optionally narrowed to
     * one operator, one action and/or one outcome.
     *
     * @param tenantId   hospital/tenant id, required
     * @param deptId     department id, required
     * @param operatorId acting subject id, or {@code null} to list every operator
     * @param action     action code, or {@code null} to list every action
     * @param outcome    outcome filter, or {@code null} to list successes and failures alike
     * @param offset     zero-based row offset, must not be negative
     * @param limit      maximum number of rows, must be strictly positive
     * @return the page content ordered by {@code created_at} descending, possibly empty
     */
    List<AuditLogDO> selectPage(@Param("tenantId") String tenantId,
                                @Param("deptId") String deptId,
                                @Param("operatorId") @Nullable String operatorId,
                                @Param("action") @Nullable String action,
                                @Param("outcome") @Nullable AuditOutcome outcome,
                                @Param("offset") long offset,
                                @Param("limit") int limit);

    /**
     * Counts the audit entries matching the same filter as
     * {@link #selectPage(String, String, String, String, AuditOutcome, long, int)}.
     *
     * @param tenantId   hospital/tenant id, required
     * @param deptId     department id, required
     * @param operatorId acting subject id, or {@code null} to count every operator
     * @param action     action code, or {@code null} to count every action
     * @param outcome    outcome filter, or {@code null} to count successes and failures alike
     * @return the total number of matching entries, never negative
     */
    long countByCondition(@Param("tenantId") String tenantId,
                          @Param("deptId") String deptId,
                          @Param("operatorId") @Nullable String operatorId,
                          @Param("action") @Nullable String action,
                          @Param("outcome") @Nullable AuditOutcome outcome);

    /**
     * Counts the audit entries already past a retention cutoff, across every tenant and department.
     *
     * <p>Serves {@code med_audit_log}'s retention trim (D55) only. It is the second query in the
     * repository without a tenant dimension (the first is the D47 session sweep), which is why
     * migration V7 adds {@code idx_med_audit_log_retention}: without it this count is a full table
     * scan of the whole trail on every run.</p>
     *
     * @param createdBefore exclusive upper bound on {@code created_at} (epoch milliseconds); rows
     *                      stamped at or after it are inside the retention window and are never
     *                      counted
     * @return the number of expired entries, never negative
     */
    long countAuditLogsCreatedBefore(@Param("createdBefore") long createdBefore);

    /**
     * Deletes at most {@code limit} audit entries past a retention cutoff, across every tenant and
     * department.
     *
     * <p>The predicate and the bound are one statement ({@code DELETE … WHERE created_at < ? LIMIT ?})
     * so a run can never remove more than one batch in one transaction. There is deliberately no
     * {@code ORDER BY}: every expired row is equally outside the window, so ordering would only add
     * work — and H2's {@code DELETE} does not support it, while the statement has to run unchanged
     * against both backends (MySQL in production, H2 in MySQL compatibility mode in tests).</p>
     *
     * @param createdBefore exclusive upper bound on {@code created_at} (epoch milliseconds)
     * @param limit         maximum number of rows this statement may delete, must be strictly
     *                      positive
     * @return the number of deleted rows; fewer than {@code limit} means the expired backlog is
     *         drained
     */
    int deleteAuditLogsCreatedBefore(@Param("createdBefore") long createdBefore,
                                     @Param("limit") int limit);
}
