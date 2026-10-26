package ai.mobiuslab.llm;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * A Jev decision request: one state, many independent questions evaluated in parallel.
 *
 * @param callerModule module/node id for traffic accounting
 * @param agentId      owning agent, nullable
 * @param modelId      null = "jev-latest"
 * @param state        String or JSON-serializable structured state
 * @param questions    question id -> question (ids are echoed back in the answers)
 */
public record JevRequest(String callerModule, String agentId, String modelId, Object state,
                         LinkedHashMap<String, JevQuestion> questions) {

    public JevRequest {
        Objects.requireNonNull(callerModule, "callerModule");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("Jev request needs at least one question");
        }
    }
}
