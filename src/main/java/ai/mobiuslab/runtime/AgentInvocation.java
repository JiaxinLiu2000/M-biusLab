package ai.mobiuslab.runtime;

import ai.mobiuslab.common.Ids;

import java.util.Map;

/**
 * A request to run an agent once. {@code invocationId} is the idempotency key: running the same id
 * twice returns the stored result instead of executing again. An input entry {@link #SEALED_KEY} = true marks
 * a sealed (held-out) run whose inputs/outputs never reach monitor payloads or the agent_run log.
 */
public record AgentInvocation(String invocationId, String traceId, Map<String, Object> input, String triggeredBy) {

    /** Input key marking a sealed run; stripped before nodes see the input. */
    public static final String SEALED_KEY = "__sealed";

    public AgentInvocation {
        if (invocationId == null) {
            invocationId = Ids.newId("inv");
        }
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    /** True when the input carries {@link #SEALED_KEY} = true. */
    public boolean sealed() {
        return Boolean.TRUE.equals(input.get(SEALED_KEY));
    }
}
