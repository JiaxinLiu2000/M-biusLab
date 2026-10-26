package ai.mobiuslab.safety;

import java.util.List;

/**
 * Outcome of a safety review.
 *
 * @param proposalId reviewed proposal
 * @param decision   ALLOW, DENY or ESCALATE (a user decision is required)
 * @param riskScore  0..1
 * @param ruleHits   ids of hard-coded rules that fired
 * @param reasons    human-readable reasons
 * @param reviewers  which reviewers contributed ("hard-rules", "jev-screen", "llm-review")
 */
public record SafetyVerdict(String proposalId, Decision decision, double riskScore, List<String> ruleHits,
                            List<String> reasons, List<String> reviewers) {

    /** Review decision. */
    public enum Decision { ALLOW, DENY, ESCALATE }

    /** True when the action may proceed. */
    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
