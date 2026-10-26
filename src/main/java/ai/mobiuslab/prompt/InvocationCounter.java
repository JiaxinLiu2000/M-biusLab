package ai.mobiuslab.prompt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free per (key, version) render counters; {@link #drain} hands pending deltas to the flusher. */
public final class InvocationCounter {

    /** Counter key. */
    public record Ref(String key, int version) {
    }

    private final Map<Ref, LongAdder> pending = new ConcurrentHashMap<>();

    /** Counts one render. */
    public void increment(String key, int version) {
        pending.computeIfAbsent(new Ref(key, version), r -> new LongAdder()).increment();
    }

    /** Not-yet-flushed count for one version. */
    public long pending(String key, int version) {
        LongAdder a = pending.get(new Ref(key, version));
        return a == null ? 0 : a.sum();
    }

    /** Removes and returns all non-zero pending deltas (concurrent increments are never lost). */
    public Map<Ref, Long> drain() {
        Map<Ref, Long> out = new LinkedHashMap<>();
        pending.forEach((ref, adder) -> {
            long n = adder.sum();
            if (n != 0) {
                adder.add(-n); // subtracting the observed sum never loses concurrent increments
                out.put(ref, n);
            }
        });
        return out;
    }

    /** Re-adds deltas whose flush failed. */
    public void restore(Map<Ref, Long> deltas) {
        deltas.forEach((ref, n) -> pending.computeIfAbsent(ref, r -> new LongAdder()).add(n));
    }
}
