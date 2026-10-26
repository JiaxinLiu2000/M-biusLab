package ai.mobiuslab.lab;

import java.time.Duration;
import java.util.Map;

/** A call into a lab tool. {@code requestId} makes retries idempotent. */
public record LabRequest(String requestId, String agentId, String operation, Map<String, Object> args,
                         Duration timeout) {
}
