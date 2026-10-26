package ai.mobiuslab.genome;

import java.time.Duration;

/**
 * How an agent is activated.
 * ON_DEMAND: invoked by another agent / topology edge. SCHEDULED: every {@code interval}.
 * EVENT: when a message is published on {@code topic}. BATCH: when {@code batchSize} items are queued
 * on {@code topic} or {@code window} elapses, whichever first.
 */
public record TriggerSpec(Kind kind, Duration interval, String topic, int batchSize, Duration window) {

    /** Activation kind. */
    public enum Kind { ON_DEMAND, SCHEDULED, EVENT, BATCH }

    /** Default trigger. */
    public static TriggerSpec onDemand() {
        return new TriggerSpec(Kind.ON_DEMAND, null, null, 0, null);
    }
}
