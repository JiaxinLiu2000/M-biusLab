package ai.mobiuslab.safety.review;

import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyProperties;
import ai.mobiuslab.safety.SafetyVerdict.Decision;
import org.springframework.stereotype.Component;

import java.util.List;

/** Structured LLM review with fixed prompts and a model family different from the master agent's. */
@Component
public class LlmSafetyReviewer {

    /** JSON schema of the reviewer's answer. */
    public static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["decision","risk","reasons"],"additionalProperties":false,
             "properties":{"decision":{"type":"string","enum":["ALLOW","DENY","ESCALATE"]},
               "risk":{"type":"number","minimum":0,"maximum":1},
               "reasons":{"type":"array","items":{"type":"string"}}}}""";

    private final LlmGateway gateway;
    private final SafetyProperties props;

    public LlmSafetyReviewer(LlmGateway gateway, SafetyProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /** Reviewer output. */
    public record Review(Decision decision, double risk, List<String> reasons) {
    }

    /** Reviews a proposal against the policy; throws MobiusException when the LLM is unavailable. */
    public Review review(ActionProposal p, String policyText, String screenNote) {
        StringBuilder user = new StringBuilder()
                .append("Action kind: ").append(p.kind()).append('\n')
                .append("Actor: ").append(p.actor().asString()).append('\n')
                .append("Target: ").append(p.target()).append('\n')
                .append("Stated intent: ").append(p.description()).append('\n');
        if (p.kind() == ActionKind.FRONTEND_CHANGE) {
            user.append("Screenshot of the result: ").append(p.attachments().get("screenshotPath"))
                    .append(" (visual inspection is not available here; judge the diff and note that a screenshot exists)\n");
        }
        if (screenNote != null) {
            user.append("Pre-screen: ").append(screenNote).append('\n');
        }
        user.append("Content:\n").append(p.content() == null ? "" : truncate(p.content(), 24_000));
        ChatRequest req = new ChatRequest("safety.llm-review", null, props.reviewModel(),
                List.of(ChatMessage.system(SafetyPrompts.get("review-system") + "\n\n" + policyText),
                        ChatMessage.user(user.toString())),
                1024, 0.0, null, null, null, props.llmTimeout());
        Review r = gateway.chatStructured(req, SCHEMA, Review.class);
        return new Review(r.decision(), Math.max(0, Math.min(1, r.risk())), r.reasons() == null ? List.of() : r.reasons());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
