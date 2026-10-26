package ai.mobiuslab.safety;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Safety-officer tuning ({@code mobius.safety.*}); every value has an in-code default.
 *
 * @param reviewModel       LLM reviewer / chat model (different family from the master's model)
 * @param jevModel          Jev model id, null = gateway default
 * @param jevConfidenceMin  minimum Jev confidence for a fast ALLOW
 * @param harmfulMax        maximum Jev "harmful" probability for a fast ALLOW
 * @param llmTimeout        timeout of one LLM review call
 * @param maxConcurrentReviews bound on concurrent reviews inside one reviewAll call
 */
@ConfigurationProperties(prefix = "mobius.safety")
public record SafetyProperties(String reviewModel, String jevModel, Double jevConfidenceMin, Double harmfulMax,
                               Duration llmTimeout, Integer maxConcurrentReviews) {

    public SafetyProperties {
        reviewModel = reviewModel == null ? "deepseek-ai/Deepseek-V4-Flash" : reviewModel;
        jevConfidenceMin = jevConfidenceMin == null ? 0.6 : jevConfidenceMin;
        harmfulMax = harmfulMax == null ? 0.3 : harmfulMax;
        llmTimeout = llmTimeout == null ? Duration.ofSeconds(60) : llmTimeout;
        maxConcurrentReviews = maxConcurrentReviews == null ? 32 : maxConcurrentReviews;
    }

    /** All defaults. */
    public static SafetyProperties defaults() {
        return new SafetyProperties(null, null, null, null, null, null);
    }
}
