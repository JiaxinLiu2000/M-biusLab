package ai.mobiuslab.runtime.node;

import java.util.List;
import java.util.Map;

/** Small typed accessors over node config maps. */
final class Cfg {

    private Cfg() {
    }

    static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    static int num(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : v == null ? def : Integer.parseInt(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
    }

    static List<String> strings(Map<String, Object> m, String key) {
        return m.get(key) instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
