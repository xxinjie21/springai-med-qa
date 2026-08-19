package com.med.qa.mapper.typehandler;

import com.med.qa.domain.enums.AuditOutcome;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis type handler persisting {@link AuditOutcome} as its numeric storage code
 * (SUCCESS=0 / FAILURE=1) in a {@code TINYINT} column.
 *
 * <p>An unknown code surfaces as {@link IllegalArgumentException} from
 * {@link AuditOutcome#fromCode(int)}, which MyBatis wraps in a {@link SQLException}: an audit entry
 * whose result cannot be interpreted must fail loudly instead of silently reading back as
 * {@code SUCCESS} — a compliance review that mistakes a rejected access for an allowed one is worse
 * than a failed query.</p>
 */
@MappedTypes(AuditOutcome.class)
public class AuditOutcomeTypeHandler extends BaseTypeHandler<AuditOutcome> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AuditOutcome parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.getCode());
    }

    @Override
    public AuditOutcome getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : AuditOutcome.fromCode(code);
    }

    @Override
    public AuditOutcome getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : AuditOutcome.fromCode(code);
    }

    @Override
    public AuditOutcome getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : AuditOutcome.fromCode(code);
    }
}
