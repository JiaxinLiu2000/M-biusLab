package ai.mobiuslab.runtime.node;

import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.util.Map;

/** OUTPUT: passes its merged input through; its values become the run output. */
@Component
public class OutputNode implements Node {

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.OUTPUT;
    }

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        return NodeOutput.of(input);
    }
}
