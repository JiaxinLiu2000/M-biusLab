package ai.mobiuslab.runtime;

import ai.mobiuslab.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC access to the append-only {@code agent_run} log; the unique invocation_id is the idempotency key. */
@Repository
public class AgentRunRepository {

    /** A stored run; {@code result} is null while RUNNING. */
    public record StoredRun(String status, AgentRunResult result) {
    }

    private static final String RUNNING = "RUNNING";

    private final JdbcClient jdbc;

    public AgentRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a RUNNING row; false when the invocation id already exists. */
    public boolean start(String invocationId, String agentId, String genomeId, String traceId, Map<String, Object> input) {
        try {
            jdbc.sql("""
                            INSERT INTO agent_run (invocation_id, agent_id, genome_id, trace_id, status, input, started_at)
                            VALUES (:inv, :agent, :genome, :trace, 'RUNNING', :input, :at)""")
                    .param("inv", invocationId).param("agent", agentId).param("genome", genomeId)
                    .param("trace", traceId).param("input", Json.write(input)).param("at", Timestamp.from(Instant.now()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Stores the final result of a run. */
    public void finish(AgentRunResult r, String traceId) {
        jdbc.sql("""
                        UPDATE agent_run SET status = :s, genome_id = COALESCE(:genome, genome_id), trace_id = COALESCE(:trace, trace_id),
                               output = :out, cost_usd = :cost, duration_ms = :dur, error = :err, finished_at = :at
                        WHERE invocation_id = :inv""")
                .param("s", r.status().name()).param("genome", r.genomeId()).param("trace", traceId)
                .param("out", Json.write(r.output())).param("cost", r.costUsd()).param("dur", r.durationMs())
                .param("err", r.error() == null ? null : r.error().length() > 2000 ? r.error().substring(0, 2000) : r.error())
                .param("at", Timestamp.from(Instant.now())).param("inv", r.invocationId()).update();
    }

    /** Run by invocation id. */
    public Optional<StoredRun> find(String invocationId) {
        return jdbc.sql("""
                        SELECT invocation_id, agent_id, genome_id, status, output, cost_usd, duration_ms, error
                        FROM agent_run WHERE invocation_id = :inv""")
                .param("inv", invocationId)
                .query((rs, i) -> {
                    String status = rs.getString("status");
                    if (RUNNING.equals(status)) {
                        return new StoredRun(status, null);
                    }
                    String out = rs.getString("output");
                    Map<String, Object> output = out == null ? Map.of() : Json.read(out, new TypeReference<Map<String, Object>>() {
                    });
                    return new StoredRun(status, new AgentRunResult(rs.getString("invocation_id"), rs.getString("agent_id"),
                            rs.getString("genome_id"), AgentRunResult.Status.valueOf(status), output,
                            rs.getDouble("cost_usd"), rs.getLong("duration_ms"), rs.getString("error")));
                }).optional();
    }

    /** Most recent runs of an agent, newest first, keyset-paged by row id (beforeId null = newest). */
    public List<AgentRunResult> recent(String agentId, Long beforeId, int limit) {
        return jdbc.sql("""
                        SELECT invocation_id, agent_id, genome_id, status, output, cost_usd, duration_ms, error
                        FROM agent_run WHERE agent_id = :a AND status <> 'RUNNING' AND (:before IS NULL OR id < :before)
                        ORDER BY id DESC LIMIT :lim""")
                .param("a", agentId).param("before", beforeId).param("lim", Math.max(1, Math.min(limit, 500)))
                .query((rs, i) -> new AgentRunResult(rs.getString(1), rs.getString(2), rs.getString(3),
                        AgentRunResult.Status.valueOf(rs.getString(4)),
                        rs.getString(5) == null ? Map.of() : Json.read(rs.getString(5), new TypeReference<Map<String, Object>>() {
                        }), rs.getDouble(6), rs.getLong(7), rs.getString(8)))
                .list();
    }
}
