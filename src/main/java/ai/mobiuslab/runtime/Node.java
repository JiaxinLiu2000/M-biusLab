package ai.mobiuslab.runtime;

import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;

import java.util.Map;

/** Executable implementation of one {@link NodeKind}; Spring beans, one per kind, stateless and thread-safe. */
public interface Node {

    /** Kind handled by this implementation. */
    NodeKind kind();

    /** Executes one node instance with the merged upstream values as input. */
    NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input);
}
