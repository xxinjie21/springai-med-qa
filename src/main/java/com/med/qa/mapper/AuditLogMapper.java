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
 * produced the entry.</p>
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
}
