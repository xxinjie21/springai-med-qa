package com.med.qa.mapper.typehandler;

import com.med.qa.domain.enums.RoleType;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis type handler persisting {@link RoleType} as its numeric storage-spec code
 * (ROADMAP section 4: PATIENT=0 / DOCTOR=1 / ASSISTANT=2 / SYSTEM=3) in a {@code TINYINT} column.
 *
 * <p>Reading an unknown code surfaces as {@link IllegalArgumentException} from
 * {@link RoleType#fromCode(int)}, which MyBatis wraps in a {@link SQLException} so the failure is
 * reported through the storage layer rather than silently corrupting the record.</p>
 */
@MappedTypes(RoleType.class)
public class RoleTypeTypeHandler extends BaseTypeHandler<RoleType> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, RoleType parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setInt(i, parameter.getCode());
    }

    @Override
    public RoleType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : RoleType.fromCode(code);
    }

    @Override
    public RoleType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : RoleType.fromCode(code);
    }

    @Override
    public RoleType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : RoleType.fromCode(code);
    }
}
