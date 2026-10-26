package ai.mobiuslab.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/** Append-only batch persistence of {@code llm_call} rows; duplicate call ids are ignored. */
@Repository
public class LlmCallRepository {

    /** One persisted LLM / Jev call. */
    public record CallRecord(String callId, Instant ts, String provider, String model, String callerModule,
                             String agentId, String promptKey, Integer promptVersion, TokenUsage usage,
                             long latencyMs, double costUsd, String status, String error, String requestId) {
    }

    private static final String INSERT = """
            INSERT IGNORE INTO llm_call (call_id, ts, provider, model, caller_module, agent_id, prompt_key,
              prompt_version, prompt_tokens, cached_tokens, completion_tokens, cache_hit_ratio, latency_ms,
              cost_usd, status, error, request_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    private final JdbcTemplate jdbc;

    public LlmCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a batch in one round trip. */
    public void insertBatch(List<CallRecord> rows) {
        jdbc.batchUpdate(INSERT, rows, rows.size(), (ps, r) -> {
            ps.setString(1, r.callId());
            ps.setTimestamp(2, Timestamp.from(r.ts()));
            ps.setString(3, r.provider());
            ps.setString(4, r.model());
            ps.setString(5, r.callerModule());
            ps.setString(6, r.agentId());
            ps.setString(7, r.promptKey());
            if (r.promptVersion() == null) {
                ps.setNull(8, Types.INTEGER);
            } else {
                ps.setInt(8, r.promptVersion());
            }
            ps.setLong(9, r.usage().promptTokens());
            ps.setLong(10, r.usage().cachedTokens());
            ps.setLong(11, r.usage().completionTokens());
            ps.setDouble(12, r.usage().cacheHitRatio());
            ps.setLong(13, r.latencyMs());
            ps.setBigDecimal(14, BigDecimal.valueOf(r.costUsd()));
            ps.setString(15, r.status());
            ps.setString(16, r.error() == null || r.error().length() <= 1000 ? r.error() : r.error().substring(0, 1000));
            ps.setString(17, r.requestId());
        });
    }
}
