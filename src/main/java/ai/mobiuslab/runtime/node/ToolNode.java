package ai.mobiuslab.runtime.node;

import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.genome.PermissionSet;
import ai.mobiuslab.lab.LabRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/** TOOL: invokes a tool allowed by genome.permissions.tools through the lab registry. */
@Component
public class ToolNode extends LabCallNode {

    public ToolNode(LabRegistry labs) {
        super(labs);
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.TOOL;
    }

    @Override
    Set<String> permitted(PermissionSet permissions) {
        return permissions.tools();
    }
}
