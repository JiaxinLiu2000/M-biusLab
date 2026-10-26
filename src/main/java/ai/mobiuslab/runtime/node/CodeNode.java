package ai.mobiuslab.runtime.node;

import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.runtime.CodeFunctionRegistry;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.util.Map;

/** CODE: calls the registered {@code CodeFunction} named by config.function with (input, config). */
@Component
public class CodeNode implements Node {

    private final CodeFunctionRegistry functions;

    public CodeNode(CodeFunctionRegistry functions) {
        this.functions = functions;
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.CODE;
    }

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        return NodeOutput.of(functions.get(Cfg.str(gene.config(), "function", null)).apply(input, gene.config()));
    }
}
