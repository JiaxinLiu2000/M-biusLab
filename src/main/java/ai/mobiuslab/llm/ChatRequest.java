package ai.mobiuslab.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A chat-completion request routed through the gateway.
 *
 * @param callerModule  module / node id making the call (for traffic accounting), e.g. "master.planner"
 * @param agentId       owning agent id, nullable for platform-level calls
 * @param modelId       exact Crusoe model id; null = gateway default for the caller
 * @param messages      stable prefix first, most volatile content last (prefix-cache friendly)
 * @param maxTokens     output budget incl. reasoning tokens; null = gateway default (>= 1024)
 * @param temperature   nullable
 * @param extraBody     provider-specific fields merged into the body (e.g. chat_template_kwargs)
 * @param promptKey     prompt-library key used to build the messages, nullable
 * @param promptVersion prompt-library version, nullable
 * @param timeout       nullable; gateway default when null
 */
public record ChatRequest(
        String callerModule,
        String agentId,
        String modelId,
        List<ChatMessage> messages,
        Integer maxTokens,
        Double temperature,
        Map<String, Object> extraBody,
        String promptKey,
        Integer promptVersion,
        Duration timeout) {

    public ChatRequest {
        Objects.requireNonNull(callerModule, "callerModule");
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }

    /** Minimal request with gateway defaults. */
    public static ChatRequest of(String callerModule, String agentId, List<ChatMessage> messages) {
        return new ChatRequest(callerModule, agentId, null, messages, null, null, null, null, null, null);
    }

    /** Copy with a specific model. */
    public ChatRequest withModel(String model) {
        return new ChatRequest(callerModule, agentId, model, messages, maxTokens, temperature, extraBody, promptKey, promptVersion, timeout);
    }

    /** Copy with prompt provenance. */
    public ChatRequest withPrompt(String key, Integer version) {
        return new ChatRequest(callerModule, agentId, modelId, messages, maxTokens, temperature, extraBody, key, version, timeout);
    }

    /** Copy with extra messages appended (used for structured-output repair turns). */
    public ChatRequest appending(List<ChatMessage> more) {
        List<ChatMessage> all = new java.util.ArrayList<>(messages);
        all.addAll(more);
        return new ChatRequest(callerModule, agentId, modelId, all, maxTokens, temperature, extraBody, promptKey, promptVersion, timeout);
    }
}
