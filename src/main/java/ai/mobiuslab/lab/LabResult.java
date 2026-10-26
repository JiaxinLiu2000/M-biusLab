package ai.mobiuslab.lab;

import java.util.Map;

/** Result of a lab tool call. */
public record LabResult(boolean ok, Map<String, Object> data, String log, long durationMs, String error) {
}
