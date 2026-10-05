package com.med.qa.mapper;

import com.med.qa.domain.entity.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MyBatis data-access mapper for chat messages, backed by the ShardingSphere-JDBC
 * {@code med_message} logical table. All physical routing to
 * {@code med_message_{crc32(session_id) % 16}} is performed transparently by ShardingSphere;
 * this interface only ever references the logical table.
 *
 * <p>The column layout is field-level aligned with the unified medical storage specification
 * (ROADMAP section 4). {@code role} is persisted as its numeric spec code via
 * {@code RoleTypeTypeHandler}; {@code metadata} is persisted as a JSON document via
 * {@code MetadataTypeHandler}.</p>
 */
@Mapper
public interface ChatMessageMapper {

    /**
     * Inserts a single chat message into the sharded {@code med_message} table.
     * ShardingSphere routes the row to {@code med_message_{crc32(session_id) % 16}}.
     *
     * @param message the message to persist, must carry a non-blank messageId and sessionId
     * @return the number of affected rows (1 on success)
     */
    int insert(ChatMessageDO message);

    /**
     * Inserts a chat message unless its {@code message_id} is already stored, in which case the
     * existing row is left untouched.
     *
     * <p>This is the primitive the conversation memory window writes through (D44). Spring AI's
     * {@code MessageWindowChatMemory} re-sends the <em>whole</em> rolling window on every turn, so
     * the window write has to be idempotent: an already persisted message must be a no-op, never a
     * duplicate row and never a reason to delete the messages that have already scrolled out of the
     * window. The statement is {@code INSERT … ON DUPLICATE KEY UPDATE message_id = message_id},
     * i.e. a duplicate-key collision is the only error it swallows — unlike {@code INSERT IGNORE},
     * which would also hide truncation and constraint failures.</p>
     *
     * @param message the message to persist, must carry a non-blank messageId and sessionId
     * @return {@code 1} when a new row was written, {@code 0} when the message was already stored
     */
    int insertIfAbsent(ChatMessageDO message);

    /**
     * Loads a message by its primary key. Because the primary key is not the sharding column,
     * ShardingSphere broadcasts the lookup across all 16 physical tables and merges the single hit.
     *
     * @param messageId the message primary key
     * @return the matching message, or {@code null} when absent
     */
    ChatMessageDO selectById(@Param("messageId") String messageId);

    /**
     * Loads every message of a session in insertion order (by {@code created_at} ascending).
     * The {@code session_id} shard key makes this a single-shard, index-backed query.
     *
     * @param sessionId the owning session id, also the sharding key
     * @return all messages of the session, possibly empty, never {@code null}
     */
    List<ChatMessageDO> selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * Loads every message of a session ordered by {@code created_at} ascending, suitable for
     * replaying a conversation into a chat memory window.
     *
     * @param sessionId the owning session id, also the sharding key
     * @return messages ordered oldest-first, possibly empty, never {@code null}
     */
    List<ChatMessageDO> selectBySessionIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);

    /**
     * Updates the privacy {@code masked} flag of a single message.
     *
     * @param messageId the message primary key
     * @param masked    the new masking state
     * @return number of affected rows (1 when the message exists)
     */
    int updateMasked(@Param("messageId") String messageId, @Param("masked") boolean masked);

    /**
     * Deletes a single message by primary key. Because the key is not the shard column,
     * ShardingSphere broadcasts the delete across all 16 physical tables.
     *
     * @param messageId the message primary key
     * @return number of affected rows (1 when the message existed)
     */
    int deleteById(@Param("messageId") String messageId);

    /**
     * Deletes every message belonging to a session. The {@code session_id} shard key makes this
     * a single-shard delete.
     *
     * @param sessionId the owning session id, also the sharding key
     * @return number of affected rows
     */
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
