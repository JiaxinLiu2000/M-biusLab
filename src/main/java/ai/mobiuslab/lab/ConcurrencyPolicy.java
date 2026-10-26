package ai.mobiuslab.lab;

/**
 * How a lab tool may be used concurrently by many agents. EXCLUSIVE: one call at a time.
 * POOLED: up to {@code permits} concurrent calls. SHARED: unlimited (tool is internally thread-safe).
 */
public record ConcurrencyPolicy(Kind kind, int permits) {

    /** Policy kind. */
    public enum Kind { EXCLUSIVE, POOLED, SHARED }

    /** One caller at a time. */
    public static ConcurrencyPolicy exclusive() {
        return new ConcurrencyPolicy(Kind.EXCLUSIVE, 1);
    }

    /** Bounded concurrency. */
    public static ConcurrencyPolicy pooled(int permits) {
        return new ConcurrencyPolicy(Kind.POOLED, permits);
    }
}
