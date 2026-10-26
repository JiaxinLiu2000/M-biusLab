package ai.mobiuslab.genome;

import java.util.Map;

/** Which models an agent uses: a default chat model, a decision model, and per-node overrides. */
public record ModelAssignment(String chatModel, String decisionModel, Map<String, String> perNode) {

    public ModelAssignment {
        perNode = perNode == null ? Map.of() : Map.copyOf(perNode);
    }

    /** Model for a given node gene id (override or default chat model). */
    public String chatModelFor(String geneId) {
        return perNode.getOrDefault(geneId, chatModel);
    }
}
