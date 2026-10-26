package ai.mobiuslab.genome;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static genome builders for tests and bootstrap code. */
public final class GenomeTemplates {

    private GenomeTemplates() {
    }

    /** INPUT -> LLM(promptKey) -> OUTPUT agent with private memory and no tools. */
    public static Genome singleLlmAgent(String agentId, String role, String description, String promptKey, String model) {
        List<NodeGene> nodes = List.of(
                node("in", NodeKind.INPUT, null, Map.of()),
                node("llm", NodeKind.LLM, promptKey, Map.of("outputKey", "text")),
                node("out", NodeKind.OUTPUT, null, Map.of()));
        List<EdgeGene> edges = List.of(new EdgeGene("in", "llm", null), new EdgeGene("llm", "out", null));
        return new Genome(null, agentId, null, null, 0, role, description,
                new ModelAssignment(model, null, null), nodes, edges, TriggerSpec.onDemand(), defaultPermissions(),
                null, WorkspaceSpec.isolated(), Map.of(), LifecycleState.DRAFT);
    }

    /** A genome with explicit nodes and edges and default permissions. */
    public static Genome graph(String agentId, String role, String model, List<NodeGene> nodes, List<EdgeGene> edges) {
        return new Genome(null, agentId, null, null, 0, role, role, new ModelAssignment(model, null, null), nodes,
                edges, TriggerSpec.onDemand(), defaultPermissions(), null, WorkspaceSpec.isolated(), Map.of(),
                LifecycleState.DRAFT);
    }

    /** A node gene whose module id is "builtin.&lt;kind&gt;" version 1. */
    public static NodeGene node(String geneId, NodeKind kind, String promptKey, Map<String, Object> config) {
        return new NodeGene(geneId, "builtin." + kind.name().toLowerCase(), 1, kind, promptKey, null, config);
    }

    /** Private read-write memory, public read, no tools/labs, no network. */
    public static PermissionSet defaultPermissions() {
        return new PermissionSet(Map.of("PRIVATE", Access.READ_WRITE, "PUBLIC", Access.READ), Set.of(), Set.of(),
                Access.NONE, false);
    }
}
