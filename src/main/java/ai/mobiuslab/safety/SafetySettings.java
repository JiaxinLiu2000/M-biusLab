package ai.mobiuslab.safety;

/**
 * User-editable safety toggles (table {@code safety_setting}).
 *
 * @param allowNetwork              when true, NETWORK actions to non-allowlisted hosts go to review instead of DENY
 * @param requireUserForAgentDelete when true, AGENT_DELETE always escalates to the user
 * @param llmReviewThreshold        Jev risk (0..1) at or above which the LLM reviewer is consulted
 * @param highRiskThreshold         risk at or above which a fail-closed review DENYs instead of escalating
 */
public record SafetySettings(Boolean allowNetwork, Boolean requireUserForAgentDelete, Double llmReviewThreshold,
                             Double highRiskThreshold) {

    public SafetySettings {
        allowNetwork = allowNetwork != null && allowNetwork;
        requireUserForAgentDelete = requireUserForAgentDelete == null || requireUserForAgentDelete;
        llmReviewThreshold = llmReviewThreshold == null ? 0.3 : llmReviewThreshold;
        highRiskThreshold = highRiskThreshold == null ? 0.7 : highRiskThreshold;
    }

    /** Default settings. */
    public static SafetySettings defaults() {
        return new SafetySettings(null, null, null, null);
    }

    /** Returns this with every non-null field of {@code patch} applied. */
    public SafetySettings merge(SafetySettings patch) {
        return patch == null ? this : new SafetySettings(
                patch.allowNetwork != null ? patch.allowNetwork : allowNetwork,
                patch.requireUserForAgentDelete != null ? patch.requireUserForAgentDelete : requireUserForAgentDelete,
                patch.llmReviewThreshold != null ? patch.llmReviewThreshold : llmReviewThreshold,
                patch.highRiskThreshold != null ? patch.highRiskThreshold : highRiskThreshold);
    }
}
