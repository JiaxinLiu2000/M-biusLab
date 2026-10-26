package ai.mobiuslab.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free rolling statistics per source id (agent, node, module): completed calls in the last
 * minute, totals, failures and average latency. Fed by TASK_END / TASK_FAIL events.
 */
public final class MonitorStats {

    private static final int BUCKETS = 60;

    private final Map<String, SourceStats> bySource = new ConcurrentHashMap<>();

    /** Immutable snapshot of one source's statistics. */
    public record Snapshot(String sourceId, String kind, long callsLastMinute, long total, long failures,
                           double avgLatencyMs) {
    }

    /** Folds an event into the statistics; only terminal span events count. */
    public void accept(MonitorEvent e) {
        if (e.type() != EventType.TASK_END && e.type() != EventType.TASK_FAIL) {
            return;
        }
        bySource.computeIfAbsent(e.sourceId(), id -> new SourceStats(e.sourceKind().name()))
                .record(e.ts().getEpochSecond(), e.latencyMs(), e.type() == EventType.TASK_FAIL);
    }

    /** Snapshot for one source, or null if never seen. */
    public Snapshot get(String sourceId) {
        SourceStats s = bySource.get(sourceId);
        return s == null ? null : s.snapshot(sourceId);
    }

    /** Snapshots of every source seen so far. */
    public List<Snapshot> all() {
        List<Snapshot> out = new ArrayList<>(bySource.size());
        bySource.forEach((id, s) -> out.add(s.snapshot(id)));
        return out;
    }

    private static final class SourceStats {
        private final String kind;
        private final LongAdder total = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder latencySum = new LongAdder();
        private final AtomicLongArray bucketSecond = new AtomicLongArray(BUCKETS);
        private final AtomicLongArray bucketCount = new AtomicLongArray(BUCKETS);

        SourceStats(String kind) {
            this.kind = kind;
        }

        void record(long epochSecond, long latencyMs, boolean failed) {
            total.increment();
            latencySum.add(latencyMs);
            if (failed) {
                failures.increment();
            }
            int idx = (int) (epochSecond % BUCKETS);
            long seen = bucketSecond.get(idx);
            if (seen != epochSecond && bucketSecond.compareAndSet(idx, seen, epochSecond)) {
                bucketCount.set(idx, 0);
            }
            bucketCount.incrementAndGet(idx);
        }

        Snapshot snapshot(String id) {
            long now = System.currentTimeMillis() / 1000;
            long lastMinute = 0;
            for (int i = 0; i < BUCKETS; i++) {
                if (now - bucketSecond.get(i) < BUCKETS) {
                    lastMinute += bucketCount.get(i);
                }
            }
            long n = total.sum();
            return new Snapshot(id, kind, lastMinute, n, failures.sum(), n == 0 ? 0 : (double) latencySum.sum() / n);
        }
    }
}
