package ai.mobiuslab.runtime.node;

import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.llm.JevAnswer;
import ai.mobiuslab.llm.JevQuestion;
import ai.mobiuslab.llm.JevRequest;
import ai.mobiuslab.llm.JevResult;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.runtime.Node;
import ai.mobiuslab.runtime.NodeContext;
import ai.mobiuslab.runtime.NodeOutput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JEV_DECISION: config.questions = {id: {type: noul|choice|score, instructions, criteria}}; asks Jev with
 * state = input and outputs flattened answers ({id: choice|noul|score, id_confidence: c}) over the input
 * (config.passThrough=false drops the input), so conditional edges like {@code route == billing} can branch.
 */
@Component
public class JevDecisionNode implements Node {

    private final LlmGateway llm;

    public JevDecisionNode(LlmGateway llm) {
        this.llm = llm;
    }

    /** {@inheritDoc} */
    @Override
    public NodeKind kind() {
        return NodeKind.JEV_DECISION;
    }

    /** {@inheritDoc} */
    @Override
    public NodeOutput execute(NodeContext ctx, NodeGene gene, Map<String, Object> input) {
        Map<String, Object> defs = Cfg.map(gene.config(), "questions");
        if (defs.isEmpty()) {
            throw MobiusException.invalid("JEV_DECISION node " + gene.geneId() + " has no questions");
        }
        LinkedHashMap<String, JevQuestion> questions = new LinkedHashMap<>();
        defs.forEach((id, def) -> questions.put(id, question(gene.geneId(), id, def)));
        String model = ctx.genome().models() == null ? null : ctx.genome().models().decisionModel();
        JevResult result = llm.decide(new JevRequest(ctx.agentId() + "/" + gene.geneId(), ctx.agentId(), model,
                input, questions));
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Boolean.FALSE.equals(gene.config().get("passThrough"))) {
            out.putAll(input);
        }
        for (String id : questions.keySet()) {
            JevAnswer a = result.answer(id);
            Object value = switch (a.type() == null ? questions.get(id).type() : a.type()) {
                case "choice" -> a.choice();
                case "noul" -> a.noul();
                default -> a.score();
            };
            out.put(id, value);
            if (a.confidence() != null) {
                out.put(id + "_confidence", a.confidence());
            }
        }
        return new NodeOutput(out, result.costUsd());
    }

    @SuppressWarnings("unchecked")
    private static JevQuestion question(String geneId, String id, Object def) {
        if (!(def instanceof Map<?, ?> raw)) {
            throw MobiusException.invalid("question " + id + " of node " + geneId + " must be an object");
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        Object instructions = m.getOrDefault("instructions", id);
        Object criteria = m.get("criteria");
        return switch (Cfg.str(m, "type", "noul")) {
            case "choice" -> {
                if (!(criteria instanceof Map<?, ?> c) || c.isEmpty()) {
                    throw MobiusException.invalid("choice question " + id + " needs criteria options");
                }
                yield new JevQuestion.Choice(instructions, new LinkedHashMap<>((Map<String, Object>) c));
            }
            case "score" -> {
                if (!(criteria instanceof List<?> l) || l.size() < 2) {
                    throw MobiusException.invalid("score question " + id + " needs 2..10 criteria levels");
                }
                yield new JevQuestion.Score(instructions, List.copyOf((List<Object>) l));
            }
            case "noul" -> new JevQuestion.Noul(instructions, criteria instanceof Map<?, ?> c ? (Map<String, Object>) c : null);
            default -> throw MobiusException.invalid("unknown question type for " + id + " in node " + geneId);
        };
    }
}
