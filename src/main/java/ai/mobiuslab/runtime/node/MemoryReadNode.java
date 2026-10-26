package ai.mobiuslab.runtime.node;

import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.memory.MemoryHit;
import ai.mobiuslab.memory.MemoryQuery;
import ai.mobiuslab.memory.MemoryScope;
import ai.mobiuslab.memory.MemoryService;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEMORY_READ: searches memory with text = input[config.queryKey|"query"] (or config.text); scopes, groups,
 * kinds and limit from config. Output {outputKey|"memories": [{id,title,body,scope,score}]}.
 */
@Component
public class MemoryReadNode implements Node {

    private final MemoryService memory;

    public MemoryReadNode(MemoryService memory) {
        this.memory = memory;
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.MEMORY_READ;
    }

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        Object q = input.get(Cfg.str(gene.config(), "queryKey", "query"));
        String text = q != null ? String.valueOf(q) : Cfg.str(gene.config(), "text", "");
        List<String> scopeNames = Cfg.strings(gene.config(), "scopes");
        List<MemoryScope> scopes = scopeNames.isEmpty() ? List.of(MemoryScope.PRIVATE, MemoryScope.GROUP, MemoryScope.PUBLIC)
                : scopeNames.stream().map(MemoryScope::valueOf).toList();
        List<MemoryHit> hits = memory.search(ctx.agentId(), new MemoryQuery(text, scopes,
                Cfg.strings(gene.config(), "groupIds"), Cfg.strings(gene.config(), "kinds"),
                Cfg.num(gene.config(), "limit", 5), false));
        List<Map<String, Object>> out = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.note().id());
            m.put("title", h.note().title());
            m.put("body", h.note().body());
            m.put("scope", h.note().scope().name());
            m.put("score", h.score());
            return m;
        }).toList();
        Map<String, Object> values = new LinkedHashMap<>(input);
        values.put(Cfg.str(gene.config(), "outputKey", "memories"), out);
        return NodeOutput.of(values);
    }
}
