package ai.mobiuslab.genome;

import java.util.Map;

/**
 * One node of an agent's internal graph.
 *
 * @param geneId        id unique within the genome, e.g. "n3"
 * @param moduleId      module-catalog id the node instantiates (reusable building block)
 * @param moduleVersion module version
 * @param kind          node kind
 * @param promptKey     prompt-library key for LLM nodes, else null
 * @param promptVersion pinned prompt version, else null
 * @param config        kind-specific configuration (JSON-serializable)
 */
public record NodeGene(String geneId, String moduleId, int moduleVersion, NodeKind kind, String promptKey,
                       Integer promptVersion, Map<String, Object> config) {

    public NodeGene {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
