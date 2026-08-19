package com.med.qa.mapper.typehandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.med.qa.domain.enums.AuditOutcome;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuditOutcomeTypeHandler}: the numeric code is written on bind and read back on
 * fetch, and an unknown code is rejected rather than silently read as SUCCESS.
 */
class AuditOutcomeTypeHandlerTest {

    private final AuditOutcomeTypeHandler handler = new AuditOutcomeTypeHandler();

    @Test
    @DisplayName("binding writes the numeric storage code")
    void setNonNullParameterWritesCode() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, AuditOutcome.SUCCESS, null);
        verify(ps).setInt(1, 0);
        handler.setNonNullParameter(ps, 2, AuditOutcome.FAILURE, null);
        verify(ps).setInt(2, 1);
    }

    @Test
    @DisplayName("reading by column name resolves a known code")
    void getNullableResultByColumn() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("outcome")).thenReturn(1);
        assertEquals(AuditOutcome.FAILURE, handler.getNullableResult(rs, "outcome"));
        when(rs.getInt("outcome")).thenReturn(0);
        assertEquals(AuditOutcome.SUCCESS, handler.getNullableResult(rs, "outcome"));
    }

    @Test
    @DisplayName("reading by column index resolves a known code")
    void getNullableResultByIndex() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt(1)).thenReturn(0);
        assertEquals(AuditOutcome.SUCCESS, handler.getNullableResult(rs, 1));
    }

    @Test
    @DisplayName("an unknown code is rejected")
    void unknownCodeRejected() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("outcome")).thenReturn(9);
        assertThrows(IllegalArgumentException.class, () -> handler.getNullableResult(rs, "outcome"));
    }
}
