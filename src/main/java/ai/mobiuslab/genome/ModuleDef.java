package ai.mobiuslab.genome;

import java.util.Map;

/** A reusable building block in the module catalog; version &lt;= 0 on register means "next version". */
public record ModuleDef(String moduleId, int version, NodeKind kind, String name, String description,
                        Map<String, Object> config, String originGenomeId, long reuseCount, double avgScore) {

    public ModuleDef {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
