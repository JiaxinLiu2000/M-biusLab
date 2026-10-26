package ai.mobiuslab.runtime;

import java.util.Map;

/** Values produced by a node plus the model/tool cost it incurred. */
public record NodeOutput(Map<String, Object> values, double costUsd) {

    public NodeOutput {
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    /** Output without cost. */
    public static NodeOutput of(Map<String, Object> values) {
        return new NodeOutput(values, 0);
    }
}
