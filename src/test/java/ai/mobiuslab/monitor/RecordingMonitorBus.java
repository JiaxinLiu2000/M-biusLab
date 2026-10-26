package ai.mobiuslab.monitor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory MonitorBus for unit tests; records every event and supports real spans. */
public final class RecordingMonitorBus implements MonitorBus {

    /** All emitted events. */
    public final List<MonitorEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void emit(MonitorEvent event) {
        events.add(event);
    }

    @Override
    public Span start(SourceKind kind, String sourceId, String agentId, String summary) {
        return new Span(this, kind, sourceId, agentId, summary);
    }

    @Override
    public void flow(SourceKind kind, String fromId, String toId, String agentId, String edgeId, Object payload) {
    }

    @Override
    public AutoCloseable subscribe(Consumer<MonitorEvent> listener) {
        return () -> {
        };
    }

    @Override
    public List<MonitorEvent> recent(int limit) {
        return List.copyOf(events);
    }

    @Override
    public MonitorStats stats() {
        return new MonitorStats();
    }
}
