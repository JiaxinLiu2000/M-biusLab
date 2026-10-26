package ai.mobiuslab.genome;

import ai.mobiuslab.common.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full, immutable definition of one agent version. Every mutation produces a new Genome with
 * {@code parentId} pointing at its predecessor, so lineage trees can be reconstructed.
 *
 * @param id          genome id ("gen_...")
 * @param agentId     agent this genome belongs to (stable across versions)
 * @param lineageId   evolution tree id (one per objective / branch family)
 * @param parentId    previous genome id, null for the first version
 * @param generation  0-based generation number
 * @param role        short role name, e.g. "researcher", "verifier", "master.planner"
 * @param description what the agent is for
 * @param models      model assignment
 * @param nodes       internal node graph nodes
 * @param edges       internal node graph edges
 * @param trigger     activation spec
 * @param permissions granted capabilities
 * @param memory      memory system spec
 * @param workspace   workspace arrangement
 * @param params      free-form tunables (temperature, thresholds, ...)
 * @param state       lifecycle state
 */
public record Genome(String id, String agentId, String lineageId, String parentId, int generation, String role,
                     String description, ModelAssignment models, List<NodeGene> nodes, List<EdgeGene> edges,
                     TriggerSpec trigger, PermissionSet permissions, MemorySpec memory, WorkspaceSpec workspace,
                     Map<String, Object> params, LifecycleState state) {

    public Genome {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        params = params == null ? Map.of() : Map.copyOf(params);
        trigger = trigger == null ? TriggerSpec.onDemand() : trigger;
        workspace = workspace == null ? WorkspaceSpec.isolated() : workspace;
        memory = memory == null ? new MemorySpec(null, null, null) : memory;
        state = state == null ? LifecycleState.DRAFT : state;
    }

    /** Content hash over the heritable fields (excludes ids, lineage, generation and state). */
    public String contentHash() {
        Map<String, Object> heritable = new LinkedHashMap<>();
        heritable.put("role", role);
        heritable.put("description", description);
        heritable.put("models", models);
        heritable.put("nodes", nodes);
        heritable.put("edges", edges);
        heritable.put("trigger", trigger);
        heritable.put("permissions", permissions);
        heritable.put("memory", memory);
        heritable.put("workspace", workspace);
        heritable.put("params", params);
        return Json.sha256(heritable);
    }

    /** Node by gene id, or null. */
    public NodeGene node(String geneId) {
        for (NodeGene n : nodes) {
            if (n.geneId().equals(geneId)) {
                return n;
            }
        }
        return null;
    }

    /** Copy with a different lifecycle state. */
    public Genome withState(LifecycleState newState) {
        return new Genome(id, agentId, lineageId, parentId, generation, role, description, models, nodes, edges,
                trigger, permissions, memory, workspace, params, newState);
    }
}
