package ai.mobiuslab.common.audit;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Append-only change history for every audited entity. Repositories call {@link #record} on each
 * insert/update/soft-delete so any row can be traced back through its recent modifications.
 */
@Service
public class RevisionLog {

    private static final int MAX_JSON = 60_000;

    private final JdbcClient jdbc;

    public RevisionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One historical change of an entity. */
    public record Revision(long id, String entityType, String entityId, String operation, String actor,
                           String reason, String beforeJson, String afterJson, Instant changedAt) {
    }

    /** Records a change; {@code before}/{@code after} are serialized canonically and may be null. */
    public void record(String entityType, String entityId, String operation, Actor actor, String reason,
                       Object before, Object after) {
        jdbc.sql("""
                        INSERT INTO entity_revision (entity_type, entity_id, operation, actor, reason, before_json, after_json, changed_at)
                        VALUES (:type, :id, :op, :actor, :reason, :before, :after, :at)""")
                .param("type", entityType)
                .param("id", entityId)
                .param("op", operation)
                .param("actor", actor.asString())
                .param("reason", truncate(reason, 1000))
                .param("before", before == null ? null : truncate(Json.write(before), MAX_JSON))
                .param("after", after == null ? null : truncate(Json.write(after), MAX_JSON))
                .param("at", java.sql.Timestamp.from(Instant.now()))
                .update();
    }

    /** Most recent {@code limit} revisions of an entity, newest first. */
    public List<Revision> recent(String entityType, String entityId, int limit) {
        return jdbc.sql("""
                        SELECT id, entity_type, entity_id, operation, actor, reason, before_json, after_json, changed_at
                        FROM entity_revision WHERE entity_type = :type AND entity_id = :id
                        ORDER BY id DESC LIMIT :limit""")
                .param("type", entityType)
                .param("id", entityId)
                .param("limit", limit)
                .query((rs, i) -> new Revision(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9).toInstant()))
                .list();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }
}
