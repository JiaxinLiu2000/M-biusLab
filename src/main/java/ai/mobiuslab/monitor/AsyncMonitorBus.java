package ai.mobiuslab.monitor;

import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Default {@link MonitorBus}: events go to a bounded queue drained by one writer thread that
 * batch-inserts into MySQL; listeners and the in-memory ring buffer are updated synchronously.
 * When the queue is full, events are still delivered live but dropped from persistence (counted).
 */
@Component
public class AsyncMonitorBus implements MonitorBus {

    private static final Logger log = LoggerFactory.getLogger(AsyncMonitorBus.class);
    private static final int MAX_PAYLOAD = 16_000;
    private static final int MAX_SUMMARY = 1000;

    private final MonitorRepository repository;
    private final BlockingQueue<MonitorEvent> queue;
    private final int batchSize;
    private final long flushMillis;
    private final int recentCapacity;
    private final Deque<MonitorEvent> recent = new ArrayDeque<>();
    private final List<Consumer<MonitorEvent>> listeners = new CopyOnWriteArrayList<>();
    private final MonitorStats stats = new MonitorStats();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;
    private volatile boolean running = true;

    public AsyncMonitorBus(MonitorRepository repository, MobiusProperties props) {
        this.repository = repository;
        MobiusProperties.Monitor cfg = props.monitor();
        this.queue = new ArrayBlockingQueue<>(cfg.queueCapacity());
        this.batchSize = cfg.batchSize();
        this.flushMillis = cfg.flushInterval().toMillis();
        this.recentCapacity = cfg.recentBuffer();
        this.writer = Thread.ofPlatform().name("monitor-writer").daemon(true).start(this::drainLoop);
    }

    @Override
    public void emit(MonitorEvent event) {
        try {
            MonitorEvent e = sanitize(event);
            stats.accept(e);
            synchronized (recent) {
                recent.addLast(e);
                while (recent.size() > recentCapacity) {
                    recent.removeFirst();
                }
            }
            for (Consumer<MonitorEvent> l : listeners) {
                try {
                    l.accept(e);
                } catch (RuntimeException ex) {
                    log.debug("monitor listener failed: {}", ex.getMessage());
                }
            }
            if (!queue.offer(e)) {
                long n = dropped.incrementAndGet();
                if (n % 1000 == 1) {
                    log.warn("monitor queue full; {} events dropped from persistence so far", n);
                }
            }
        } catch (RuntimeException ex) {
            log.error("monitor emit failed (event discarded): {}", ex.getMessage());
        }
    }

    @Override
    public Span start(SourceKind kind, String sourceId, String agentId, String summary) {
        return new Span(this, kind, sourceId, agentId, summary);
    }

    @Override
    public void flow(SourceKind kind, String fromId, String toId, String agentId, String edgeId, Object payload) {
        String json = payload == null ? null : (payload instanceof String s ? s : Json.write(payload));
        long bytes = json == null ? 0 : json.length();
        Span parent = Span.current();
        MonitorEvent e = MonitorEvent.of(kind, fromId, agentId, EventType.DATA_FLOW, edgeId == null ? null : "edge " + edgeId)
                .withTarget(toId).withPayload(json).withMetrics("OK", 0, bytes);
        if (parent != null) {
            e = e.withTrace(parent.traceId(), null, parent.spanId());
        }
        emit(e);
    }

    @Override
    public AutoCloseable subscribe(Consumer<MonitorEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public List<MonitorEvent> recent(int limit) {
        synchronized (recent) {
            int skip = Math.max(0, recent.size() - limit);
            List<MonitorEvent> out = new ArrayList<>(Math.min(limit, recent.size()));
            int i = 0;
            for (MonitorEvent e : recent) {
                if (i++ >= skip) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    @Override
    public MonitorStats stats() {
        return stats;
    }

    /** Number of events that could not be queued for persistence. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Flushes remaining events on shutdown. */
    @PreDestroy
    public void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush(new ArrayList<>(queue));
    }

    private void drainLoop() {
        List<MonitorEvent> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                MonitorEvent first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                }
                if (!batch.isEmpty()) {
                    flush(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flush(List<MonitorEvent> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.insertBatch(batch);
        } catch (RuntimeException e) {
            log.error("monitor batch insert failed ({} events lost): {}", batch.size(), e.getMessage());
        }
    }

    private static MonitorEvent sanitize(MonitorEvent e) {
        String summary = e.summary();
        String payload = e.payload();
        boolean changed = false;
        if (summary != null && summary.length() > MAX_SUMMARY) {
            summary = summary.substring(0, MAX_SUMMARY - 3) + "...";
            changed = true;
        }
        if (payload != null && payload.length() > MAX_PAYLOAD) {
            payload = payload.substring(0, MAX_PAYLOAD) + "...[truncated]";
            changed = true;
        }
        if (!changed) {
            return e;
        }
        return new MonitorEvent(e.eventId(), e.ts(), e.traceId(), e.spanId(), e.parentSpanId(), e.sourceKind(),
                e.sourceId(), e.agentId(), e.type(), e.targetId(), e.status(), e.latencyMs(), e.bytes(), summary, payload);
    }
}
