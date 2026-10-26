package ai.mobiuslab.monitor;

import ai.mobiuslab.common.Ids;
import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;

/**
 * A unit of observed work. Created by {@link MonitorBus#start}; emits TASK_END on {@link #ok} /
 * {@link #close} or TASK_FAIL on {@link #fail}. Exactly one terminal event is emitted (idempotent).
 * Use with try-with-resources.
 */
public final class Span implements AutoCloseable {

    private static final ThreadLocal<Deque<Span>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private final MonitorBus bus;
    private final SourceKind kind;
    private final String sourceId;
    private final String agentId;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final long startNanos;
    private volatile boolean finished;

    Span(MonitorBus bus, SourceKind kind, String sourceId, String agentId, String summary) {
        this.bus = bus;
        this.kind = kind;
        this.sourceId = sourceId;
        this.agentId = agentId;
        Span parent = current();
        this.traceId = parent != null ? parent.traceId : Ids.newId("trc");
        this.parentSpanId = parent != null ? parent.spanId : null;
        this.spanId = Ids.newId("spn");
        this.startNanos = System.nanoTime();
        STACK.get().push(this);
        MDC.put("traceId", traceId);
        if (agentId != null) {
            MDC.put("agentId", agentId);
        }
        bus.emit(MonitorEvent.of(kind, sourceId, agentId, EventType.TASK_START, summary)
                .withTrace(traceId, spanId, parentSpanId).withMetrics("RUNNING", 0, 0));
    }

    /** Innermost open span on this thread, or null. */
    public static Span current() {
        Deque<Span> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Runs {@code body} on the current thread with {@code parent} as the innermost span (and its traceId/agentId
     * in the MDC), so spans opened inside join the parent's trace; the thread's previous span stack and MDC are
     * restored afterwards. A null parent just runs the body.
     */
    public static <T> T callWithParent(Span parent, Callable<T> body) throws Exception {
        if (parent == null) {
            return body.call();
        }
        Deque<Span> previous = STACK.get();
        String prevTrace = MDC.get("traceId");
        String prevAgent = MDC.get("agentId");
        Deque<Span> stack = new ArrayDeque<>();
        stack.push(parent);
        STACK.set(stack);
        MDC.put("traceId", parent.traceId);
        if (parent.agentId != null) {
            MDC.put("agentId", parent.agentId);
        }
        try {
            return body.call();
        } finally {
            STACK.set(previous);
            restore("traceId", prevTrace);
            restore("agentId", prevAgent);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /** Trace id shared by all nested spans. */
    public String traceId() {
        return traceId;
    }

    /** This span's id. */
    public String spanId() {
        return spanId;
    }

    /** Emits an extra event inside this span (inherits trace linkage). */
    public void event(EventType type, String targetId, String summary, String payloadJson) {
        bus.emit(MonitorEvent.of(kind, sourceId, agentId, type, summary)
                .withTrace(traceId, Ids.newId("spn"), spanId).withTarget(targetId).withPayload(payloadJson));
    }

    /** Marks the span successful with a result summary. */
    public void ok(String summary) {
        finish(EventType.TASK_END, "OK", summary);
    }

    /** Marks the span failed. */
    public void fail(Throwable error) {
        finish(EventType.TASK_FAIL, "FAIL", error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    /** Closes the span as OK if no terminal status was reported yet. */
    @Override
    public void close() {
        finish(EventType.TASK_END, "OK", null);
    }

    private synchronized void finish(EventType type, String status, String summary) {
        if (finished) {
            return;
        }
        finished = true;
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        bus.emit(MonitorEvent.of(kind, sourceId, agentId, type, summary)
                .withTrace(traceId, spanId, parentSpanId).withMetrics(status, latencyMs, 0));
        Deque<Span> stack = STACK.get();
        stack.remove(this);
        if (stack.isEmpty()) {
            MDC.remove("traceId");
            MDC.remove("agentId");
            STACK.remove();
        }
    }
}
