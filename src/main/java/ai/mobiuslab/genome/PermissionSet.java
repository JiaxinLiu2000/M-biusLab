package ai.mobiuslab.genome;

import java.util.Map;
import java.util.Set;

/**
 * Capabilities granted to an agent by the master agent.
 *
 * @param memory          memory-scope key -> access. Keys: "PRIVATE", "PUBLIC", "GROUP:&lt;groupId&gt;"
 * @param tools           tool ids the agent may call
 * @param labs            lab tool ids the agent may use
 * @param sharedWorkspace access to the shared workspace
 * @param network         whether the agent may make outbound network requests (still safety-gated)
 */
public record PermissionSet(Map<String, Access> memory, Set<String> tools, Set<String> labs,
                            Access sharedWorkspace, boolean network) {

    public PermissionSet {
        memory = memory == null ? Map.of() : Map.copyOf(memory);
        tools = tools == null ? Set.of() : Set.copyOf(tools);
        labs = labs == null ? Set.of() : Set.copyOf(labs);
        sharedWorkspace = sharedWorkspace == null ? Access.NONE : sharedWorkspace;
    }

    /** Access for a memory-scope key, NONE if not granted. */
    public Access memoryAccess(String scopeKey) {
        return memory.getOrDefault(scopeKey, Access.NONE);
    }
}
