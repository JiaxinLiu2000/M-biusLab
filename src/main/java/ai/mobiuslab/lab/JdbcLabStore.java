package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.audit.RevisionLog;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL-backed {@link LabStore} over {@code lab_tool} and {@code lab_call}. */
@Repository
public class JdbcLabStore implements LabStore {

    private static final int MAX_LOG = 16_000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final RevisionLog revisions;

    /** Spring constructor. */
    public JdbcLabStore(JdbcClient jdbc, RevisionLog revisions) {
        this.jdbc = jdbc;
        this.revisions = revisions;
    }

    @Override
    public Optional<LabResult> findFinished(String requestId) {
        return jdbc.sql("SELECT status, result, log, duration_ms FROM lab_call WHERE request_id = :r")
                .param("r", requestId)
                .query((rs, i) -> {
                    String json = rs.getString(2);
                    Map<String, Object> stored = json == null ? Map.of() : Json.read(json, MAP);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) stored.get("data");
                    return new LabResult("OK".equals(rs.getString(1)), data, rs.getString(3), rs.getLong(4),
                            (String) stored.get("error"));
                })
                .optional();
    }

    @Override
    public void saveCall(CallRow c) {
        LabResult r = c.result();
        java.util.HashMap<String, Object> stored = new java.util.HashMap<>();
        if (r.data() != null) {
            stored.put("data", r.data());
        }
        if (r.error() != null) {
            stored.put("error", r.error());
        }
        String logText = r.log();
        if (logText != null && logText.length() > MAX_LOG) {
            logText = logText.substring(0, MAX_LOG) + "...[truncated]";
        }
        jdbc.sql("""
                        INSERT IGNORE INTO lab_call (request_id, tool_id, agent_id, operation, args, status, result, log,
                            duration_ms, queued_ms, created_at, finished_at)
                        VALUES (:r, :t, :a, :o, :args, :s, :res, :log, :d, :q, :c, :f)""")
                .param("r", c.requestId())
                .param("t", c.toolId())
                .param("a", c.agentId())
                .param("o", c.operation())
                .param("args", c.args() == null ? null : Json.write(c.args()))
                .param("s", r.ok() ? "OK" : "FAIL")
                .param("res", Json.write(stored))
                .param("log", logText)
                .param("d", r.durationMs())
                .param("q", c.queuedMs())
                .param("c", Timestamp.from(c.createdAt()))
                .param("f", Timestamp.from(Instant.now()))
                .update();
    }

    @Override
    @Transactional
    public void saveTool(ToolRow t, Actor actor) {
        Optional<ToolRow> before = find(t.id());
        if (before.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO lab_tool (id, description, policy_kind, permits, origin, owner_agent_id, spec, created_by, updated_by)
                            VALUES (:id, :d, :k, :p, :o, :own, :spec, :by, :by)""")
                    .params(params(t, actor))
                    .update();
            revisions.record("lab_tool", t.id(), "INSERT", actor, "register tool", null, t);
        } else {
            jdbc.sql("""
                            UPDATE lab_tool SET description = :d, policy_kind = :k, permits = :p, origin = :o,
                                owner_agent_id = :own, spec = :spec, updated_by = :by, row_version = row_version + 1,
                                deleted = 0, deleted_at = NULL, deleted_by = NULL
                            WHERE id = :id""")
                    .params(params(t, actor))
                    .update();
            revisions.record("lab_tool", t.id(), "UPDATE", actor, "re-register tool", before.get(), t);
        }
    }

    @Override
    public List<ToolRow> agentTools() {
        return jdbc.sql("""
                        SELECT id, description, policy_kind, permits, origin, owner_agent_id, spec
                        FROM lab_tool WHERE origin = 'agent' AND deleted = 0 ORDER BY id""")
                .query((rs, i) -> row(rs))
                .list();
    }

    private Optional<ToolRow> find(String id) {
        return jdbc.sql("SELECT id, description, policy_kind, permits, origin, owner_agent_id, spec FROM lab_tool WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> row(rs))
                .optional();
    }

    private static ToolRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String spec = rs.getString(7);
        return new ToolRow(rs.getString(1), rs.getString(2),
                new ConcurrencyPolicy(ConcurrencyPolicy.Kind.valueOf(rs.getString(3)), rs.getInt(4)),
                rs.getString(5), rs.getString(6), spec == null ? Map.of() : Json.read(spec, MAP));
    }

    private static Map<String, Object> params(ToolRow t, Actor actor) {
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("id", t.id());
        m.put("d", t.description());
        m.put("k", t.policy().kind().name());
        m.put("p", t.policy().permits());
        m.put("o", t.origin());
        m.put("own", t.ownerAgentId());
        m.put("spec", t.spec() == null ? null : Json.write(t.spec()));
        m.put("by", actor.asString());
        return m;
    }
}
