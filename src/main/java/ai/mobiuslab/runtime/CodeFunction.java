package ai.mobiuslab.runtime;

import java.util.Map;

/** Pure Java function callable from CODE nodes by name; register implementations as Spring beans. */
public interface CodeFunction {

    /** Unique function name, e.g. "text.template". */
    String name();

    /** Computes output values from node input and node config. */
    Map<String, Object> apply(Map<String, Object> in, Map<String, Object> config);
}
