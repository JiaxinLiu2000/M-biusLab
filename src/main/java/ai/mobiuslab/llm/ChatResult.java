package ai.mobiuslab.llm;

/** Result of a chat completion. {@code reasoning} holds reasoning_content / reasoning when present. */
public record ChatResult(
        String content,
        String reasoning,
        String finishReason,
        TokenUsage usage,
        String modelId,
        long latencyMs,
        double costUsd,
        String requestId) {
}
