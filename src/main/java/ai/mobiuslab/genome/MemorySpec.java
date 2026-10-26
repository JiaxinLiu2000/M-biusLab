package ai.mobiuslab.genome;

import java.util.List;
import java.util.Map;

/**
 * Memory system of an agent. {@code backend} is "obsidian" (default) or the id of a custom memory
 * backend the agents created; {@code groups} are the memory groups the agent belongs to.
 */
public record MemorySpec(String backend, List<String> groups, Map<String, Object> config) {

    public MemorySpec {
        backend = backend == null ? "obsidian" : backend;
        groups = groups == null ? List.of() : List.copyOf(groups);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
