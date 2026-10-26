package ai.mobiuslab.monitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Batch persistence and paged queries for monitor events (append-only table). */
@Repository
public class MonitorRepository {

    private static final String INSERT = """
            INSERT IGNORE INTO monitor_event (event_id, ts, trace_id, span_id, parent_span_id, source_kind, source_id,
              agent_id, event_type, target_id, status, latency_ms, bytes, summary, payload)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    private static final String COLUMNS = "event_id, ts, trace_id, span_id, parent_span_id, source_kind, source_id, "
            + "agent_id, event_type, target_id, status, latency_ms, bytes, summary, payload";

    private static final RowMapper<MonitorEvent> MAPPER = (rs, i) -> new MonitorEvent(
            rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5),
            SourceKind.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8), EventType.valueOf(rs.getString(9)),
            rs.getString(10), rs.getString(11), rs.getLong(12), rs.getLong(13), rs.getString(14), rs.getString(15));

    private final JdbcTemplate jdbc;

    public MonitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a batch in one round trip; duplicate event ids are ignored (idempotent). */
    public void insertBatch(List<MonitorEvent> events) {
        jdbc.batchUpdate(INSERT, events, events.size(), (ps, e) -> {
            ps.setString(1, e.eventId());
            ps.setTimestamp(2, Timestamp.from(e.ts()));
            ps.setString(3, e.traceId());
            ps.setString(4, e.spanId());
            ps.setString(5, e.parentSpanId());
            ps.setString(6, e.sourceKind().name());
            ps.setString(7, e.sourceId());
            ps.setString(8, e.agentId());
            ps.setString(9, e.type().name());
            ps.setString(10, e.targetId());
            ps.setString(11, e.status());
            ps.setLong(12, e.latencyMs());
            ps.setLong(13, e.bytes());
            ps.setString(14, e.summary());
            ps.setString(15, e.payload());
        });
    }

    /** Events of one trace in time order. */
    public List<MonitorEvent> byTrace(String traceId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM monitor_event WHERE trace_id = ? ORDER BY id LIMIT ?",
                MAPPER, traceId, limit);
    }

    /** Keyset-paged events for one agent after a given time (use the last ts as the next cursor). */
    public List<MonitorEvent> byAgentSince(String agentId, Instant since, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM monitor_event WHERE agent_id = ? AND ts > ? ORDER BY ts LIMIT ?",
                MAPPER, agentId, Timestamp.from(since), limit);
    }

    /** Keyset-paged events across the platform after a given time. */
    public List<MonitorEvent> since(Instant since, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM monitor_event WHERE ts > ? ORDER BY ts LIMIT ?",
                MAPPER, Timestamp.from(since), limit);
    }
}
