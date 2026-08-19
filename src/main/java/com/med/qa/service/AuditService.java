package com.med.qa.service;

import com.med.qa.audit.AuditRecord;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Persists the medical operation audit trail into the single {@code med_audit_log} table.
 *
 * <h2>What the service owns</h2>
 * <p>Translating an {@link AuditRecord} into a storable row: minting the primary key, stamping the
 * audit time from an injected {@link Clock} and truncating the free-text fields to the physical column
 * widths declared by {@link MedAuditProperties}. Truncation is deliberate — an over-long failure
 * message from a downstream library must not turn a completed medical operation into a failed insert,
 * and a clipped message is still evidence.</p>
 *
 * <h2>Failure semantics</h2>
 * <p>A storage failure is reported as {@link ErrorCode#STORAGE_ERROR}, consistent with every other
 * MySQL write in this project: the service does not decide that a lost audit entry is acceptable. The
 * caller does — {@link com.med.qa.audit.AuditAspect} catches the failure so the business outcome the
 * user already earned is neither replaced by an audit error nor silently reported as successful,
 * while the incident stays visible in the application log.</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogMapper auditLogMapper;

    private final MedAuditProperties properties;

    private final Clock clock;

    private final Supplier<String> auditIdGenerator;

    /**
     * Creates the service used by the application context.
     *
     * @param auditLogMapper MyBatis mapper over {@code med_audit_log}, must not be {@code null}
     * @param properties     truncation limits and master switch, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    @Autowired
    public AuditService(AuditLogMapper auditLogMapper, MedAuditProperties properties) {
        this(auditLogMapper, properties, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    /**
     * Creates the service with an explicit clock, so audit timestamps are deterministic in tests.
     *
     * @param auditLogMapper MyBatis mapper over {@code med_audit_log}, must not be {@code null}
     * @param properties     truncation limits and master switch, must not be {@code null}
     * @param clock          clock stamping {@code created_at}, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public AuditService(AuditLogMapper auditLogMapper, MedAuditProperties properties, Clock clock) {
        this(auditLogMapper, properties, clock, () -> UUID.randomUUID().toString());
    }

    /**
     * Creates the service with an explicit clock and audit-id generator.
     *
     * @param auditLogMapper   MyBatis mapper over {@code med_audit_log}, must not be {@code null}
     * @param properties       truncation limits and master switch, must not be {@code null}
     * @param clock            clock stamping {@code created_at}, must not be {@code null}
     * @param auditIdGenerator supplier of primary keys, must not be {@code null}
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public AuditService(AuditLogMapper auditLogMapper,
                        MedAuditProperties properties,
                        Clock clock,
                        Supplier<String> auditIdGenerator) {
        if (auditLogMapper == null) {
            throw new IllegalArgumentException("auditLogMapper must not be null");
        }
        if (properties == null) {
            throw new IllegalArgumentException("properties must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (auditIdGenerator == null) {
            throw new IllegalArgumentException("auditIdGenerator must not be null");
        }
        this.auditLogMapper = auditLogMapper;
        this.properties = properties;
        this.clock = clock;
        this.auditIdGenerator = auditIdGenerator;
    }

    /**
     * Appends one audit entry to the trail.
     *
     * @param record the operation to record, must not be {@code null}
     * @return the persisted row, carrying its generated id and audit timestamp
     * @throws IllegalArgumentException if {@code record} is {@code null}
     * @throws BizException {@link ErrorCode#STORAGE_ERROR} when the insert failed
     */
    public AuditLogDO record(AuditRecord record) {
        AuditLogDO row = toRow(record);
        try {
            auditLogMapper.insert(row);
        } catch (DataAccessException ex) {
            throw new BizException(ErrorCode.STORAGE_ERROR,
                    "failed to persist audit log for action " + row.getAction(), ex);
        }
        if (log.isDebugEnabled()) {
            log.debug("audit {} action={} target={}/{} outcome={} latency={}ms",
                    row.getAuditId(), row.getAction(), row.getResourceType(), row.getResourceId(),
                    row.getOutcome(), row.getLatencyMillis());
        }
        return row;
    }

    /**
     * Converts an audit record into the row that will be written, applying the configured truncation
     * limits.
     *
     * <p>Exposed so the mapping (id generation, timestamp stamping, clipping) can be asserted without
     * a database, and so a caller that batches entries elsewhere reuses the very same normalization.</p>
     *
     * @param record the operation to convert, must not be {@code null}
     * @return a fully populated, column-safe row
     * @throws IllegalArgumentException if {@code record} is {@code null}
     */
    public AuditLogDO toRow(AuditRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        AuditLogDO row = new AuditLogDO();
        row.setAuditId(auditIdGenerator.get());
        row.setTenantId(record.tenantId());
        row.setDeptId(record.deptId());
        row.setOperatorId(record.operatorId());
        row.setOperatorRole(record.operatorRole() == null ? null : record.operatorRole().name());
        row.setAction(truncate(record.action(), properties.getMaxActionLength()));
        row.setResourceType(truncate(record.resourceType(), properties.getMaxResourceTypeLength()));
        row.setResourceId(truncate(record.resourceId(), properties.getMaxResourceIdLength()));
        row.setOutcome(record.outcome());
        row.setErrorCode(record.errorCode());
        row.setLatencyMillis(record.latencyMillis());
        row.setMessage(truncate(record.message(), properties.getMaxMessageLength()));
        row.setCreatedAt(clock.millis());
        return row;
    }

    @Nullable
    private static String truncate(@Nullable String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
