package com.med.qa.mapper.typehandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MyBatis type handler serializing the message {@code metadata} map as a JSON document stored in a
 * {@code LONGTEXT} column. A {@code null} or empty map is persisted as {@code "{}"} and read back as
 * an empty {@link LinkedHashMap}, so downstream codecs never face {@code null} metadata.
 *
 * <p>JSON (not a custom format) keeps the column human-readable and lets the heterogeneous Python
 * middleware interoperate without sharing code.</p>
 */
@MappedTypes(Map.class)
public class MetadataTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            Map<String, String> safe = parameter == null ? new LinkedHashMap<>() : parameter;
            ps.setString(i, MAPPER.writeValueAsString(safe));
        } catch (Exception ex) {
            throw new SQLException("failed to serialize chat message metadata to JSON", ex);
        }
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String raw = rs.getString(columnName);
        return parse(raw);
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String raw = rs.getString(columnIndex);
        return parse(raw);
    }

    @Override
    public Map<String, String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String raw = cs.getString(columnIndex);
        return parse(raw);
    }

    private Map<String, String> parse(String raw) throws SQLException {
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<?, ?> generic = MAPPER.readValue(raw, LinkedHashMap.class);
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : generic.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
            return normalized;
        } catch (Exception ex) {
            throw new SQLException("failed to parse chat message metadata from JSON: " + raw, ex);
        }
    }
}
