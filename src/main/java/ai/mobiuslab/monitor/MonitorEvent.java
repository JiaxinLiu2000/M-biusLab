package ai.mobiuslab.monitor;

import ai.mobiuslab.common.Ids;

import java.time.Instant;
import java.util.Objects;

/**
 * One observable fact reported to the monitor: a task start/end, a message, a data flow between
 * nodes or agents, a tool call, a memory access, an LLM call, a safety verdict, etc.
 *
 * @param eventId     unique id (idempotency key; duplicates are ignored on persist)
 * @param ts          event time
 * @param traceId     end-to-end trace (one user goal / one run)
 * @param spanId      the unit of work emitting the event
 * @param parentSpanId enclosing unit of work, if any
 * @param sourceKind  what kind of component emitted it
 * @param sourceId    id of the emitter (agent id, "agentId/geneId" for nodes, module name, ...)
 * @param agentId     owning agent, if any
 * @param type        event type
 * @param targetId    receiver for flows/messages/tool calls (agent id, node id, tool id, note id)
 * @param status      OK / FAIL / RUNNING / DENIED ...
 * @param latencyMs   duration for *_END events, else 0
 * @param bytes       payload size for flow events, else 0
 * @param summary     short human-readable description (<= 1000 chars)
 * @param payload     optional JSON detail (truncated by the bus)
 */
public record MonitorEvent(
        String eventId,
        Instant ts,
        String traceId,
        String spanId,
        String parentSpanId,
        SourceKind sourceKind,
        String sourceId,
        String agentId,
        EventType type,
        String targetId,
        String status,
        long latencyMs,
        long bytes,
        String summary,
        String payload) {

    public MonitorEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        if (eventId == null) {
            eventId = Ids.newId("evt");
        }
        if (ts == null) {
            ts = Instant.now();
        }
        if (status == null) {
            status = "OK";
        }
    }

    /** Convenience builder for a simple event without span/flow details. */
    public static MonitorEvent of(SourceKind kind, String sourceId, String agentId, EventType type, String summary) {
        return new MonitorEvent(null, null, null, null, null, kind, sourceId, agentId, type, null, null, 0, 0, summary, null);
    }

    /** Copy with a target id. */
    public MonitorEvent withTarget(String target) {
        return new MonitorEvent(eventId, ts, traceId, spanId, parentSpanId, sourceKind, sourceId, agentId, type, target, status, latencyMs, bytes, summary, payload);
    }

    /** Copy with a JSON payload. */
    public MonitorEvent withPayload(String json) {
        return new MonitorEvent(eventId, ts, traceId, spanId, parentSpanId, sourceKind, sourceId, agentId, type, targetId, status, latencyMs, bytes, summary, json);
    }

    /** Copy with trace/span linkage. */
    public MonitorEvent withTrace(String trace, String span, String parent) {
        return new MonitorEvent(eventId, ts, trace, span, parent, sourceKind, sourceId, agentId, type, targetId, status, latencyMs, bytes, summary, payload);
    }

    /** Copy with status, latency and byte size. */
    public MonitorEvent withMetrics(String newStatus, long latency, long size) {
        return new MonitorEvent(eventId, ts, traceId, spanId, parentSpanId, sourceKind, sourceId, agentId, type, targetId, newStatus, latency, size, summary, payload);
    }
}
