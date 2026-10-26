package ai.mobiuslab.runtime.node;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.PermissionSet;
import ai.mobiuslab.lab.LabRegistry;
import ai.mobiuslab.lab.LabRequest;
import ai.mobiuslab.lab.LabResult;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared TOOL/LAB logic: checks config.toolId against the genome permissions, then invokes the lab registry
 * with args = input + config.args and request id {@code invocationId/geneId} (idempotent retries).
 */
abstract class LabCallNode implements Node {

    private final LabRegistry labs;

    LabCallNode(LabRegistry labs) {
        this.labs = labs;
    }

    /** Permitted tool ids for this kind. */
    abstract Set<String> permitted(PermissionSet permissions);

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        String toolId = Cfg.str(gene.config(), "toolId", null);
        if (toolId == null) {
            throw MobiusException.invalid(kind() + " node " + gene.geneId() + " has no toolId");
        }
        PermissionSet perms = ctx.genome().permissions();
        if (perms == null || !permitted(perms).contains(toolId)) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "agent " + ctx.agentId() + " may not use " + kind() + " " + toolId);
        }
        Map<String, Object> args = new LinkedHashMap<>(input);
        args.putAll(Cfg.map(gene.config(), "args"));
        LabResult r = labs.invoke(toolId, new LabRequest(ctx.invocation().invocationId() + "/" + gene.geneId(),
                ctx.agentId(), Cfg.str(gene.config(), "operation", "run"), args,
                Duration.ofMillis(Cfg.num(gene.config(), "timeoutMs", 120_000))));
        if (!r.ok()) {
            throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, toolId + " failed: " + r.error());
        }
        return NodeOutput.of(r.data());
    }
}
