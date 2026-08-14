package com.med.qa.mapper;

import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.enums.SessionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * MyBatis data-access mapper for consultation sessions, backed by the single (non-sharded)
 * {@code med_session} table.
 *
 * <p>Sessions deliberately stay in one table: they are read by primary key and listed per
 * patient/department, so the paged listing remains an index-backed query instead of a 16-way
 * scatter-gather over the sharded message tables. ShardingSphere-JDBC routes the table through its
 * {@code SINGLE} rule.</p>
 *
 * <p>The column layout is field-level aligned with the unified medical storage specification
 * (ROADMAP section 4) and with the {@code ChatSession} message of {@code med_session.proto}.
 * {@code status} is persisted as its numeric spec code via {@code SessionStatusTypeHandler}.</p>
 */
@Mapper
public interface ChatSessionMapper {

    /**
     * Inserts a new session row.
     *
     * @param session the session to persist; must carry non-blank {@code sessionId},
     *                {@code tenantId}, {@code deptId}, {@code patientId} and a non-null status
     * @return the number of affected rows (1 on success)
     */
    int insert(ChatSessionDO session);

    /**
     * Loads a session by its primary key.
     *
     * @param sessionId the session primary key
     * @return the matching session, or {@code null} when absent
     */
    ChatSessionDO selectById(@Param("sessionId") String sessionId);

    /**
     * Moves a session to a new lifecycle status, but only while it still holds the expected one.
     *
     * <p>The {@code expectedStatus} predicate makes the update a compare-and-set: two concurrent
     * close requests can both pass the read-side check, yet only one of them changes a row, so the
     * caller can tell an effective transition from a no-op.</p>
     *
     * @param sessionId      the session primary key
     * @param status         the new lifecycle status
     * @param expectedStatus the status the row must currently hold
     * @param updatedAt      new update timestamp as epoch milliseconds
     * @return number of affected rows (1 when the transition was applied, 0 when the row was already
     *         in another state)
     */
    int updateStatus(@Param("sessionId") String sessionId,
                     @Param("status") SessionStatus status,
                     @Param("expectedStatus") SessionStatus expectedStatus,
                     @Param("updatedAt") long updatedAt);

    /**
     * Lists one page of sessions of a tenant/department, newest first, optionally narrowed to one
     * patient and/or one lifecycle status.
     *
     * @param tenantId  hospital/tenant id, required
     * @param deptId    department id, required
     * @param patientId patient id, or {@code null} to list the whole department
     * @param status    lifecycle status, or {@code null} to list every status
     * @param offset    zero-based row offset, must not be negative
     * @param limit     maximum number of rows, must be strictly positive
     * @return the page content ordered by {@code created_at} descending, possibly empty
     */
    List<ChatSessionDO> selectPage(@Param("tenantId") String tenantId,
                                   @Param("deptId") String deptId,
                                   @Param("patientId") @Nullable String patientId,
                                   @Param("status") @Nullable SessionStatus status,
                                   @Param("offset") long offset,
                                   @Param("limit") int limit);

    /**
     * Counts the sessions matching the same filter as
     * {@link #selectPage(String, String, String, SessionStatus, long, int)}.
     *
     * @param tenantId  hospital/tenant id, required
     * @param deptId    department id, required
     * @param patientId patient id, or {@code null} to count the whole department
     * @param status    lifecycle status, or {@code null} to count every status
     * @return the total number of matching sessions, never negative
     */
    long countByCondition(@Param("tenantId") String tenantId,
                          @Param("deptId") String deptId,
                          @Param("patientId") @Nullable String patientId,
                          @Param("status") @Nullable SessionStatus status);
}
