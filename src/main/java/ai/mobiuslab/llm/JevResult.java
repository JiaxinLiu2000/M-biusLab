package ai.mobiuslab.llm;

import java.util.Map;

/** Result of a Jev decision request; answers keyed by the caller's question ids. */
public record JevResult(String model, Map<String, JevAnswer> answers, TokenUsage usage, long latencyMs,
                        double costUsd, String requestId) {

    /** Answer for one question id; throws if missing. */
    public JevAnswer answer(String questionId) {
        JevAnswer a = answers.get(questionId);
        if (a == null) {
            throw new IllegalStateException("Jev answer missing for question " + questionId);
        }
        return a;
    }
}
