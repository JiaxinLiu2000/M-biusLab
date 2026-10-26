package ai.mobiuslab.llm;

import java.util.Map;

/**
 * A typed Jev answer. Exactly one of noul/choice/score is set according to {@code type}.
 * {@code probabilities} keys are options (choice) or level indexes as strings (score).
 */
public record JevAnswer(String type, Double noul, String choice, Double score,
                        Map<String, Double> probabilities, Double confidence) {

    /** True when a noul answer is >= threshold. */
    public boolean yes(double threshold) {
        return noul != null && noul >= threshold;
    }
}
