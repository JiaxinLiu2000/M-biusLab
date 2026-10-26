package ai.mobiuslab.runtime.node;

import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.genome.PermissionSet;
import ai.mobiuslab.lab.LabRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/** LAB: invokes a tool allowed by genome.permissions.labs through the lab registry. */
@Component
public class LabNode extends LabCallNode {

    public LabNode(LabRegistry labs) {
        super(labs);
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.LAB;
    }

    @Override
    Set<String> permitted(PermissionSet permissions) {
        return permissions.labs();
    }
}
