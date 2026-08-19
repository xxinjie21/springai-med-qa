package com.med.qa.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.audit.AuditRecord;
import com.med.qa.common.exception.BizException;
import com.med.qa.common.exception.ErrorCode;
import com.med.qa.config.MedAuditProperties;
import com.med.qa.domain.entity.AuditLogDO;
import com.med.qa.domain.enums.AuditOutcome;
import com.med.qa.mapper.AuditLogMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Unit tests for {@link AuditService}: the {@code record} persistence path (id minting, timestamp,
 * truncation, storage-failure classification) and the public {@code toRow} mapping.
 */
class AuditServiceTest {

    private final AuditLogMapper mapper = Mockito.mock(AuditLogMapper.class);

    private final MedAuditProperties properties = new MedAuditProperties();

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final AtomicInteger counter = new AtomicInteger();

    private final Supplier<String> idGenerator = () -> "id-" + counter.incrementAndGet();

    private final AuditService service = new AuditService(mapper, properties, clock, idGenerator);

    @Test
    @DisplayName("record persists a row, mints the id and stamps the audit time")
    void recordPersistsAndStamps() {
        AuditRecord record = AuditRecord.builder("SESSION_VIEW")
                .tenantId("t").deptId("d").operatorId("o")
                .resource("SESSION", "s1")
                .success("ok")
                .latencyMillis(2)
                .build();

        AuditLogDO row = service.record(record);

        verify(mapper).insert(any(AuditLogDO.class));
        assertEquals("id-1", row.getAuditId());
        assertEquals(1_700_000_000_000L, row.getCreatedAt());
        assertEquals("SESSION_VIEW", row.getAction());
        assertEquals("SESSION", row.getResourceType());
        assertEquals("s1", row.getResourceId());
        assertEquals(AuditOutcome.SUCCESS, row.getOutcome());
    }

    @Test
    @DisplayName("record rejects a null argument")
    void recordRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> service.record(null));
    }

    @Test
    @DisplayName("a storage failure is classified as STORAGE_ERROR, never swallowed")
    void recordClassifiesStorageError() {
        when(mapper.insert(any())).thenThrow(new DataAccessResourceFailureException("boom"));
        AuditRecord record = AuditRecord.builder("SESSION_VIEW")
                .tenantId("t").deptId("d").operatorId("o")
                .success("ok").latencyMillis(1).build();
        BizException ex = assertThrows(BizException.class, () -> service.record(record));
        assertEquals(ErrorCode.STORAGE_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("toRow truncates over-long fields to the configured column widths")
    void toRowTruncates() {
        AuditRecord record = AuditRecord.builder("A".repeat(200))
                .tenantId("t").deptId("d").operatorId("o")
                .resource("R".repeat(100), "I".repeat(300))
                .failure(12345, "M".repeat(600))
                .latencyMillis(4)
                .build();
        AuditLogDO row = service.toRow(record);
        assertEquals(MedAuditProperties.ACTION_COLUMN_LENGTH, row.getAction().length());
        assertEquals(MedAuditProperties.RESOURCE_TYPE_COLUMN_LENGTH, row.getResourceType().length());
        assertEquals(MedAuditProperties.RESOURCE_ID_COLUMN_LENGTH, row.getResourceId().length());
        assertEquals(MedAuditProperties.MESSAGE_COLUMN_LENGTH, row.getMessage().length());
        assertEquals(12345, row.getErrorCode());
        assertEquals(AuditOutcome.FAILURE, row.getOutcome());
    }

    @Test
    @DisplayName("toRow leaves within-limit fields intact")
    void toRowKeepsWithinLimit() {
        AuditRecord record = AuditRecord.builder("SHORT")
                .tenantId("t").deptId("d").operatorId("o")
                .resource("SESS", "abc")
                .success("hi")
                .latencyMillis(1)
                .build();
        AuditLogDO row = service.toRow(record);
        assertEquals("SHORT", row.getAction());
        assertEquals("SESS", row.getResourceType());
        assertEquals("abc", row.getResourceId());
        assertEquals("hi", row.getMessage());
    }

    @Test
    @DisplayName("toRow rejects a null argument")
    void toRowRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> service.toRow(null));
    }
}
