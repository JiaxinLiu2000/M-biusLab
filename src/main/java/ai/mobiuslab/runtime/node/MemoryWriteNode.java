package ai.mobiuslab.runtime.node;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.memory.MemoryNote;
import ai.mobiuslab.memory.MemoryScope;
import ai.mobiuslab.memory.MemoryService;
import ai.mobiuslab.memory.MemoryWrite;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MEMORY_WRITE: writes a note with body = input[config.bodyKey|"text"], title = input[config.titleKey|"title"]
 * (or config.title), scope/groupId/kind/tags from config. Output = input + {noteId}.
 */
@Component
public class MemoryWriteNode implements Node {

    private final MemoryService memory;

    public MemoryWriteNode(MemoryService memory) {
        this.memory = memory;
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.MEMORY_WRITE;
    }

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        Map<String, Object> c = gene.config();
        Object body = input.get(Cfg.str(c, "bodyKey", "text"));
        Object title = input.get(Cfg.str(c, "titleKey", "title"));
        MemoryWrite w = new MemoryWrite(MemoryScope.valueOf(Cfg.str(c, "scope", "PRIVATE")), Cfg.str(c, "groupId", null),
                Cfg.str(c, "kind", "note"), title != null ? String.valueOf(title) : Cfg.str(c, "title", gene.geneId()),
                body == null ? "" : String.valueOf(body), Cfg.strings(c, "tags"), Cfg.strings(c, "links"),
                ctx.agentId() + "/" + gene.geneId() + "@" + ctx.invocation().invocationId(),
                c.get("confidence") instanceof Number n ? n.doubleValue() : 0.7);
        MemoryNote note = memory.write(Actor.agent(ctx.agentId()), ctx.agentId(), w);
        Map<String, Object> values = new LinkedHashMap<>(input);
        values.put("noteId", note.id());
        return NodeOutput.of(values);
    }
}
