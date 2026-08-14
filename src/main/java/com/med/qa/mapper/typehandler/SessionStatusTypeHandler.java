package com.med.qa.mapper.typehandler;

import com.med.qa.domain.enums.SessionStatus;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis type handler persisting {@link SessionStatus} as its numeric storage-spec code
 * (ACTIVE=0 / CLOSED=1 / ARCHIVED=2, aligned with the {@code SessionState} enum of
 * {@code med_session.proto}) in a {@code TINYINT} column.
 *
 * <p>Reading an unknown code surfaces as {@link IllegalArgumentException} from
 * {@link SessionStatus#fromCode(int)}, which MyBatis wraps in a {@link SQLException}: a session whose
 * lifecycle state cannot be interpreted must fail loudly rather than silently default to
 * {@code ACTIVE} and accept new medical messages.</p>
 */
@MappedTypes(SessionStatus.class)
public class SessionStatusTypeHandler extends BaseTypeHandler<SessionStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, SessionStatus parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.getCode());
    }

    @Override
    public SessionStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : SessionStatus.fromCode(code);
    }

    @Override
    public SessionStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : SessionStatus.fromCode(code);
    }

    @Override
    public SessionStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : SessionStatus.fromCode(code);
    }
}
