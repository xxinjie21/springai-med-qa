package com.med.qa.service;

import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.common.result.PageResult;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Reads the medical operation audit trail for a compliance review.
 *
 * <h2>Why this service exists</h2>
 * <p>{@link AuditLogMapper#selectPage} / {@link AuditLogMapper#countByCondition} have existed since D23
 * together with their XML, and both the mapper javadoc and the V3 migration comment describe them as
 * the data access of an audit review — yet until D56 nothing called them: the trail could be written
 * (D23) and trimmed (D55) but never read back through the application. This service is that missing
 * call site, and it is the only one.</p>
 *
 * <h2>Scope</h2>
 * <p>Every query carries a mandatory tenant/department pair straight into the WHERE clause of both
 * statements; there is deliberately no "read the whole trail" or "read one entry by id" primitive, so
 * a review cannot escape the caller's own department even by constructing the query object by hand.
 * Which caller may reach which department is decided one layer up, by the {@code @RequireDept}
 * interceptor on the controller (D22) — the same split the session listing uses.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>A MySQL failure is reported as {@link ErrorCode#STORAGE_ERROR}, consistent with every other
 * MySQL read in this project. A page beyond the end of the result set is not an error: it returns an
 * empty page carrying the real total, so a reviewer paging past the end can still render a pager.</p>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogMapper auditLogMapper;

    private final MedAuditProperties properties;

    /**
     * Creates the service used by the application context.
     *
     * @param auditLogMapper MyBatis mapper over {@code med_audit_log}, must not be {@code null}
     * @param properties     audit tuning (page-size bounds), must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    @Autowired
    public AuditLogService(AuditLogMapper auditLogMapper, MedAuditProperties properties) {
        if (auditLogMapper == null) {
            throw new IllegalArgumentException("auditLogMapper must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        this.auditLogMapper = auditLogMapper;
        this.properties = properties;
    }

    /**
     * Lists one page of audit entries of one department, newest first.
     *
     * <p>The count and the page run as two statements over the same filter set, so the reported total
     * always matches what the page was drawn from. An out-of-range page yields an empty page with the
     * real total rather than an error.</p>
     *
     * @param query the review request, must not be {@code null}
     * @return the requested page, never {@code null}
     * @throws IllegalArgumentException if {@code query} is {@code null}
     * @throws BizException             {@link ErrorCode#BAD_REQUEST} when the requested size is not
     *                                  positive or exceeds
     *                                  {@link MedAuditProperties#getMaxPageSize()};
     *                                  {@link ErrorCode#STORAGE_ERROR} on a MySQL failure
     */
    public PageResult<AuditLogDO> review(AuditLogPageQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        int size = resolvePageSize(query.size());
        long total;
        try {
            total = auditLogMapper.countByCondition(
                    query.tenantId(), query.deptId(), query.operatorId(), query.action(),
                    query.outcome(), query.createdFrom(), query.createdTo());
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to count the audit trail of department " + query.deptId(), ex);
        }
        long offset = (long) (query.page() - 1) * size;
        if (total <= 0 || offset >= total) {
            return PageResult.of(query.page(), size, Math.max(total, 0L), Collections.emptyList());
        }
        List<AuditLogDO> records;
        try {
            records = auditLogMapper.selectPage(
                    query.tenantId(), query.deptId(), query.operatorId(), query.action(),
                    query.outcome(), query.createdFrom(), query.createdTo(), offset, size);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to list the audit trail of department " + query.deptId(), ex);
        }
        PageResult<AuditLogDO> result = PageResult.of(query.page(), size, total,
                records == null ? Collections.emptyList() : records);
        if (log.isDebugEnabled()) {
            log.debug("audit review dept={} operator={} action={} outcome={} window=[{}, {}) "
                            + "page={}/{} total={}",
                    query.deptId(), query.operatorId(), query.action(), query.outcome(),
                    query.createdFrom(), query.createdTo(), query.page(), size, total);
        }
        return result;
    }

    /**
     * Resolves the effective page size of a review request.
     *
     * @param requested the size asked for, or {@code null} to apply the configured default
     * @return the effective page size, always positive
     * @throws BizException {@link ErrorCode#BAD_REQUEST} when the requested size is not positive or
     *                      exceeds {@link MedAuditProperties#getMaxPageSize()}
     */
    public int resolvePageSize(@Nullable Integer requested) {
        if (requested == null) {
            return properties.getDefaultPageSize();
        }
        if (requested < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "page size must be positive but was " + requested);
        }
        if (requested > properties.getMaxPageSize()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "page size " + requested + " exceeds the configured maximum "
                            + properties.getMaxPageSize());
        }
        return requested;
    }
}
