package ai.mobiuslab.safety;

import java.util.List;

/**
 * The safety officer's review entry point. Hard-coded rules run first (obvious dangers are denied
 * without any model call); remaining proposals are screened by Jev and, when ambiguous or high-risk,
 * by an LLM reviewer. Reviews of many proposals run concurrently. Every verdict is persisted and
 * reported to the monitor.
 */
public interface SafetyGate {

    /** Reviews one proposal. Never returns null. */
    SafetyVerdict review(ActionProposal proposal);

    /** Reviews proposals concurrently; result order matches input order. */
    List<SafetyVerdict> reviewAll(List<ActionProposal> proposals);

    /**
     * Reviews and throws MobiusException(SAFETY_DENIED) on DENY or MobiusException(NEEDS_USER_DECISION)
     * on ESCALATE; returns the verdict on ALLOW.
     */
    SafetyVerdict require(ActionProposal proposal);
}
