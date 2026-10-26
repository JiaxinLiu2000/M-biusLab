package ai.mobiuslab.memory.store;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.memory.MemoryNote;
import ai.mobiuslab.memory.MemoryScope;
import ai.mobiuslab.memory.ScopeKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL implementation of {@link MemoryNoteStore} using hand-written SQL; thread-safe. */
@Repository
public class JdbcMemoryNoteStore implements MemoryNoteStore {

    private static final String ENTITY = "memory_note";
    private static final String COLS = """
            id, scope, owner_agent_id, group_id, kind, title, file_path, tags, links, source, confidence,
            invalidated, superseded_by, body_excerpt, index_pending, created_at""";
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final RevisionLog revisions;

    /** Creates the store. */
    public JdbcMemoryNoteStore(JdbcClient jdbc, RevisionLog revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    /** {@inheritDoc} */
    @Override
    public boolean insert(Row row, String contentHash, Actor actor) {
        MemoryNote n = row.note();
        try {
            jdbc.sql("""
                            INSERT INTO memory_note (id, scope, owner_agent_id, group_id, kind, title, file_path, tags, links,
                              source, confidence, invalidated, superseded_by, content_hash, scope_key, body_excerpt,
                              index_pending, created_at, created_by, updated_by)
                            VALUES (:id, :scope, :owner, :grp, :kind, :title, :path, :tags, :links, :source, :conf, :inv,
                              :sup, :hash, :skey, :excerpt, :pending, :created, :by, :by)""")
                    .param("id", n.id()).param("scope", n.scope().name()).param("owner", n.ownerAgentId())
                    .param("grp", n.groupId()).param("kind", n.kind()).param("title", cut(n.title(), 300))
                    .param("path", row.filePath()).param("tags", Json.write(n.tags())).param("links", Json.write(n.links()))
                    .param("source", cut(n.source(), 1000)).param("conf", n.confidence()).param("inv", n.invalidated())
                    .param("sup", n.supersededBy()).param("hash", contentHash).param("skey", ScopeKeys.of(n))
                    .param("excerpt", cut(n.body(), 2000)).param("pending", row.indexPending())
                    .param("created", Timestamp.from(n.createdAt())).param("by", actor.asString())
                    .update();
        } catch (DuplicateKeyException e) {
            return false;
        }
        revisions.record(ENTITY, n.id(), "CREATE", actor, null, null, n);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Row> find(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM memory_note WHERE id = :id AND deleted = 0")
                .param("id", id).query(this::map).optional();
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> findAll(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM memory_note WHERE id IN (:ids) AND deleted = 0")
                .param("ids", ids).query(this::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> findDuplicate(String ownerAgentId, String scopeKey, String contentHash) {
        return jdbc.sql("""
                        SELECT id FROM memory_note
                        WHERE owner_agent_id = :o AND scope_key = :k AND content_hash = :h AND deleted = 0""")
                .param("o", ownerAgentId).param("k", scopeKey).param("h", contentHash)
                .query(String.class).optional();
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate(String id, String reason, String supersededBy, Actor actor) {
        Row before = find(id).orElse(null);
        jdbc.sql("""
                        UPDATE memory_note SET invalidated = 1, invalidated_reason = :r, superseded_by = :s,
                          updated_by = :by, row_version = row_version + 1 WHERE id = :id AND deleted = 0""")
                .param("r", cut(reason, 1000)).param("s", supersededBy).param("by", actor.asString()).param("id", id)
                .update();
        revisions.record(ENTITY, id, "INVALIDATE", actor, reason, before == null ? null : before.note(),
                Map.of("invalidated", true, "supersededBy", supersededBy == null ? "" : supersededBy));
    }

    /** {@inheritDoc} */
    @Override
    public void setIndexPending(String id, boolean pending) {
        jdbc.sql("UPDATE memory_note SET index_pending = :p WHERE id = :id").param("p", pending).param("id", id).update();
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> pendingIndex(int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM memory_note WHERE index_pending = 1 AND deleted = 0 ORDER BY id LIMIT :l")
                .param("l", limit).query(this::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> textCandidates(List<String> tokens, List<String> allowedScopeKeys, List<String> kinds,
                                    boolean includeInvalidated, int limit) {
        if (allowedScopeKeys.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM memory_note WHERE deleted = 0 AND scope_key IN (:keys)");
        Map<String, Object> params = new HashMap<>();
        params.put("keys", allowedScopeKeys);
        if (!includeInvalidated) {
            sql.append(" AND invalidated = 0");
        }
        if (kinds != null && !kinds.isEmpty()) {
            sql.append(" AND kind IN (:kinds)");
            params.put("kinds", kinds);
        }
        List<String> terms = tokens.stream().filter(t -> t.length() >= 2).limit(12).toList();
        if (!terms.isEmpty()) {
            List<String> ors = new ArrayList<>();
            for (int i = 0; i < terms.size(); i++) {
                ors.add("title LIKE :t" + i + " OR body_excerpt LIKE :t" + i);
                params.put("t" + i, "%" + terms.get(i).replace("%", "\\%").replace("_", "\\_") + "%");
            }
            sql.append(" AND (").append(String.join(" OR ", ors)).append(')');
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> page(Map<String, String> filter, String afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM memory_note WHERE deleted = 0 AND id > :after");
        Map<String, Object> params = new HashMap<>();
        params.put("after", afterId == null ? "" : afterId);
        if (filter != null) {
            if (filter.get("scope") != null) {
                sql.append(" AND scope = :scope");
                params.put("scope", filter.get("scope").toUpperCase());
            }
            if (filter.get("groupId") != null) {
                sql.append(" AND group_id = :grp");
                params.put("grp", filter.get("groupId"));
            }
            if (filter.get("ownerAgentId") != null) {
                sql.append(" AND owner_agent_id = :owner");
                params.put("owner", filter.get("ownerAgentId"));
            }
        }
        sql.append(" ORDER BY id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(this::map).list();
    }

    private Row map(ResultSet rs, int i) throws SQLException {
        MemoryNote n = new MemoryNote(rs.getString("id"), MemoryScope.valueOf(rs.getString("scope")),
                rs.getString("owner_agent_id"), rs.getString("group_id"), rs.getString("kind"), rs.getString("title"),
                rs.getString("body_excerpt"), list(rs.getString("tags")), list(rs.getString("links")),
                rs.getString("source"), rs.getDouble("confidence"), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("invalidated"), rs.getString("superseded_by"));
        return new Row(n, rs.getString("file_path"), rs.getBoolean("index_pending"));
    }

    private static List<String> list(String json) {
        return json == null ? List.of() : Json.read(json, STR_LIST);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
