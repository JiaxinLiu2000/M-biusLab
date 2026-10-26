package ai.mobiuslab.monitor;

import java.util.List;
import java.util.function.Consumer;

/**
 * The single reporting interface for every agent, node and module. Emission is non-blocking and
 * never throws: monitoring must not break the work it observes.
 */
public interface MonitorBus {

    /** Enqueues an event for persistence and live fan-out. */
    void emit(MonitorEvent event);

    /**
     * Opens a span: emits TASK_START now and TASK_END / TASK_FAIL when closed. Nested spans inherit the
     * trace id of the innermost open span on the current thread unless {@code traceId} is given.
     */
    Span start(SourceKind kind, String sourceId, String agentId, String summary);

    /** Reports a payload moving from one component to another (node to node, agent to agent, ...). */
    void flow(SourceKind kind, String fromId, String toId, String agentId, String edgeId, Object payload);

    /** Registers a live listener (e.g. an SSE stream); close the returned handle to unsubscribe. */
    AutoCloseable subscribe(Consumer<MonitorEvent> listener);

    /** Most recent events held in memory, oldest first. */
    List<MonitorEvent> recent(int limit);

    /** Rolling per-source statistics (calls/min, average latency, failures). */
    MonitorStats stats();
}
