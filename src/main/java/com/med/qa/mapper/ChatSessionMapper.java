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

    /**
     * Lists the sessions of <em>every</em> tenant and department that are still in the given status
     * although nothing has happened to them for a while, oldest first.
     *
     * <p>This is the candidate query of the retention sweep (D47), so it is the one query in this
     * mapper that is deliberately not scoped to a tenant/department: a retention job has no request
     * context to derive a scope from, and an abandoned consultation in one department is exactly as
     * abandoned as one in another. The staleness predicate lives in the SQL
     * ({@code status = :status AND updated_at <= :idleBefore}) rather than in the caller, so the
     * database can answer it from {@code idx_med_session_retention} instead of the JVM filtering a
     * full table scan. The status is a parameter rather than a literal so the numeric spec code keeps
     * exactly one source of truth ({@code SessionStatusTypeHandler}).</p>
     *
     * <p>Ordering by {@code updated_at} ascending matters: when the sweep cannot keep up with the
     * backlog, the sessions that have been idle longest are archived first.</p>
     *
     * @param status                the lifecycle status to select, normally
     *                              {@link SessionStatus#ACTIVE}
     * @param idleBeforeEpochMillis cutoff as epoch milliseconds; a session is stale when its
     *                              {@code updated_at} is less than or equal to this instant
     * @param limit                 maximum number of rows, must be strictly positive
     * @return the stale sessions ordered by {@code updated_at} ascending, possibly empty, never
     *         {@code null}
     */
    List<ChatSessionDO> selectStaleSessions(@Param("status") SessionStatus status,
                                            @Param("idleBefore") long idleBeforeEpochMillis,
                                            @Param("limit") int limit);

    /**
     * Counts the sessions {@link #selectStaleSessions(SessionStatus, long, int)} would return, without
     * fetching them.
     *
     * <p>Used to report the backlog size of a sweep, which is what tells an operator whether
     * {@code med.session.retention.max-batches} is set high enough to drain it.</p>
     *
     * @param status                the lifecycle status to count, normally
     *                              {@link SessionStatus#ACTIVE}
     * @param idleBeforeEpochMillis cutoff as epoch milliseconds, same predicate as the selection
     * @return the number of stale sessions in that status, never negative
     */
    long countStaleSessions(@Param("status") SessionStatus status,
                            @Param("idleBefore") long idleBeforeEpochMillis);

    /**
     * Moves a session to a new status, but only while it still holds the expected status
     * <em>and</em> has not been updated since the given instant.
     *
     * <p>This is the primitive the retention sweep archives through (D47). The staleness predicate is
     * part of the same {@code UPDATE} as the status change on purpose: a sweep reads its candidates
     * first and archives them afterwards, so between the two steps a patient may come back and ask
     * another question. Checking "is it still stale?" in Java and then issuing a plain status update
     * would archive that live consultation; folding {@code updated_at <= :idleBefore} into the
     * {@code WHERE} clause makes the database reject the write instead, whatever the caller did in
     * between. The sweep additionally holds the session lock shared with the message-append path, but
     * this predicate is what makes the operation safe even for a writer that bypassed the lock.</p>
     *
     * @param sessionId      the session primary key
     * @param status         the new lifecycle status
     * @param expectedStatus the status the row must currently hold
     * @param idleBeforeEpochMillis the row must not have been updated after this epoch-millisecond
     *                              instant
     * @param updatedAt      new update timestamp as epoch milliseconds
     * @return number of affected rows: {@code 1} when the transition was applied, {@code 0} when the
     *         row was already in another state or had been refreshed in the meantime
     */
    int updateStatusIfStale(@Param("sessionId") String sessionId,
                            @Param("status") SessionStatus status,
                            @Param("expectedStatus") SessionStatus expectedStatus,
                            @Param("idleBefore") long idleBeforeEpochMillis,
                            @Param("updatedAt") long updatedAt);
}
