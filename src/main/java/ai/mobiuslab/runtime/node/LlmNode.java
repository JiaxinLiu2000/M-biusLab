package ai.mobiuslab.runtime.node;

import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.ChatResult;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.prompt.PromptLibrary;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM: renders the gene's prompt (pinned or latest version) with input + config.vars and calls the gateway;
 * defaults {@code role}, {@code description} (from the genome) and {@code input} (canonical JSON of the node input)
 * are provided unless the input or config.vars set them. With config.jsonSchema the structured reply map is the
 * output, else {outputKey|text: content}.
 */
@Component
public class LlmNode implements Node {

    private final PromptLibrary prompts;
    private final LlmGateway llm;

    public LlmNode(PromptLibrary prompts, LlmGateway llm) {
        this.prompts = prompts;
        this.llm = llm;
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.LLM;
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        if (gene.promptKey() == null) {
            throw MobiusException.invalid("LLM node " + gene.geneId() + " has no promptKey");
        }
        int version = gene.promptVersion() != null ? gene.promptVersion() : prompts.latest(gene.promptKey()).version();
        Map<String, Object> vars = new HashMap<>();
        vars.put("role", ctx.genome().role() == null ? "" : ctx.genome().role());
        vars.put("description", ctx.genome().description() == null ? "" : ctx.genome().description());
        vars.put("input", Json.write(input));
        vars.putAll(input);
        vars.putAll(Cfg.map(gene.config(), "vars"));
        List<ChatMessage> messages = prompts.render(gene.promptKey(), version, vars);
        String model = ctx.genome().models() == null ? null : ctx.genome().models().chatModelFor(gene.geneId());
        Object maxTokens = gene.config().get("maxTokens");
        Object temperature = gene.config().get("temperature");
        Duration timeout = ctx.deadline() == null ? null : Duration.between(Instant.now(), ctx.deadline());
        ChatRequest req = new ChatRequest(ctx.agentId() + "/" + gene.geneId(), ctx.agentId(), model, messages,
                maxTokens instanceof Number n ? n.intValue() : null,
                temperature instanceof Number t ? t.doubleValue() : null, null, gene.promptKey(), version,
                timeout != null && !timeout.isNegative() ? timeout : null);
        Object schema = gene.config().get("jsonSchema");
        if (schema != null) {
            String schemaJson = schema instanceof String s ? s : Json.write(schema);
            Map<String, Object> out = llm.chatStructured(req, schemaJson, Map.class);
            return NodeOutput.of(new LinkedHashMap<>(out));
        }
        ChatResult r = llm.chat(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(Cfg.str(gene.config(), "outputKey", "text"), r.content());
        mergeJsonObject(r.content(), out);
        return new NodeOutput(out, r.costUsd());
    }

    /** Adds the fields of a JSON-object reply (optionally fenced) without overwriting existing keys. */
    @SuppressWarnings("unchecked")
    static void mergeJsonObject(String content, Map<String, Object> out) {
        if (content == null) {
            return;
        }
        String s = content.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            s = nl > 0 && end > nl ? s.substring(nl + 1, end).strip() : s;
        }
        if (!s.startsWith("{") || !s.endsWith("}")) {
            return;
        }
        try {
            Map<String, Object> parsed = Json.read(s, Map.class);
            parsed.forEach(out::putIfAbsent);
        } catch (RuntimeException ignored) {
            // not a JSON object: keep the plain text output only
        }
    }
}
