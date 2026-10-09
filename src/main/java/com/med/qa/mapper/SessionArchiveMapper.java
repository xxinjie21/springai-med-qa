package com.med.qa.mapper;

import com.med.qa.domain.entity.ArchivedMessageDO;
import com.med.qa.domain.entity.ChatSessionDO;
import com.med.qa.domain.entity.SessionArchiveManifestDO;
import com.med.qa.domain.enums.SessionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis data-access mapper for the cold transcript archive (D48), backed by the two non-sharded
 * tables {@code med_message_archive} and {@code med_session_archive}.
 *
 * <p>Neither table is sharded, and that is deliberate: the archive is read one session at a time, and
 * the composite primary key {@code (session_id, message_id)} turns a session's transcript into one
 * contiguous range read instead of a scatter-gather across the 16 {@code med_message} shards. The
 * sharding rule exists to keep the write path of <em>live</em> consultations cheap; cold data has no
 * write path. ShardingSphere-JDBC routes both tables through its {@code SINGLE} rule.</p>
 *
 * <p>The candidate query crosses the two domains ({@code med_session} and
 * {@code med_session_archive}) on purpose, and it is the reason this mapper exists at all rather than
 * the export living on {@link ChatSessionMapper}: the session mapper must not know that an archive
 * exists. See {@link #selectUnexportedArchivedSessions(SessionStatus, int)} for why the "already
 * exported?" filter has to travel inside the SQL.</p>
 */
@Mapper
public interface SessionArchiveMapper {

    /**
     * Lists one page of archived sessions that have no export manifest yet, oldest first.
     *
     * <p>The {@code NOT EXISTS} predicate is the whole point of the query. The obvious alternative -
     * page through {@code med_session} ordered by {@code updated_at} and then ask, row by row,
     * whether a manifest exists - does not work: the oldest archived sessions are exactly the ones a
     * previous run already exported, so the job would fetch the same fully exported page on every run
     * and never reach the sessions behind it. Pushing the filter into the SQL makes the database skip
     * them while walking the index.</p>
     *
     * <p>Ordering is {@code updated_at} ascending (then {@code session_id} as a tie-break, so the page
     * boundary is stable when many sessions share a millisecond) so a backlog drains from the
     * sessions that have been cold the longest.</p>
     *
     * @param status the lifecycle status to select, normally {@link SessionStatus#ARCHIVED}
     * @param limit  maximum number of rows, must be strictly positive
     * @return the unexported archived sessions, possibly empty, never {@code null}
     */
    List<ChatSessionDO> selectUnexportedArchivedSessions(@Param("status") SessionStatus status,
                                                         @Param("limit") int limit);

    /**
     * Counts the sessions {@link #selectUnexportedArchivedSessions(SessionStatus, int)} would return.
     *
     * <p>Reported as the backlog size of an export run, which is what tells an operator whether
     * {@code med.session.archive.max-batches} is set high enough to drain it.</p>
     *
     * @param status the lifecycle status to count, normally {@link SessionStatus#ARCHIVED}
     * @return the number of archived sessions without a manifest, never negative
     */
    long countUnexportedArchivedSessions(@Param("status") SessionStatus status);

    /**
     * Copies one message into the cold store unless it is already there.
     *
     * <p>The table's primary key is {@code (session_id, message_id)}, so a repeated export of the same
     * transcript is a duplicate-key no-op instead of a read-then-write check. The statement is
     * {@code INSERT … ON DUPLICATE KEY UPDATE message_id = message_id}, the same shape as the D44
     * window write: a duplicate-key collision is the only error it swallows, unlike {@code INSERT
     * IGNORE}, which would also hide truncation and other constraint failures. This is also what makes
     * a retry after a failed verification converge - the messages already copied are no-ops and only
     * the missing ones are added.</p>
     *
     * @param message the archive row to persist, must carry non-blank ids and a non-empty payload
     * @return {@code 1} when a new row was written, {@code 0} when the message was already archived
     */
    int insertIfAbsent(ArchivedMessageDO message);

    /**
     * Reads the archived transcript of a session in its original order.
     *
     * <p>Ordered by {@code (created_at, message_id)}, i.e. a total order: two messages written in the
     * same millisecond would otherwise come back in an arbitrary order and the recomputed checksum
     * would differ between two reads of an unchanged transcript.</p>
     *
     * @param sessionId the owning session id
     * @return the archived messages ordered oldest-first, possibly empty, never {@code null}
     */
    List<ArchivedMessageDO> selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * Writes the export manifest of a session unless one already exists.
     *
     * <p>The primary key is {@code session_id}, so this is a compare-and-set on "has this session been
     * exported?": the caller can tell an effective export from one another run already performed, and
     * a manifest a verification is currently reading is never overwritten.</p>
     *
     * @param manifest the manifest to persist
     * @return {@code 1} when the manifest was written, {@code 0} when the session was already exported
     */
    int insertManifestIfAbsent(SessionArchiveManifestDO manifest);

    /**
     * Loads the export manifest of a session.
     *
     * @param sessionId the exported session id
     * @return the manifest, or {@code null} when the session was never exported
     */
    SessionArchiveManifestDO selectManifest(@Param("sessionId") String sessionId);
}
