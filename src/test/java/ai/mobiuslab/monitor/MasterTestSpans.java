package ai.mobiuslab.monitor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Test-only in-memory MonitorBus that can create real spans (Span's constructor is package-private). */
public final class MasterTestSpans implements MonitorBus {

    /** Captured events. */
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
        return () -> { };
    }

    @Override
    public List<MonitorEvent> recent(int limit) {
        return List.copyOf(events);
    }

    @Override
    public MonitorStats stats() {
        return null;
    }
}
