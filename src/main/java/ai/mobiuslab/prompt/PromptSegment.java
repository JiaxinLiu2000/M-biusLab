package ai.mobiuslab.prompt;

import ai.mobiuslab.llm.ChatMessage;

/**
 * One piece of a prompt template. Text may reference variables as {{name}}. Segments are rendered in
 * ascending {@code volatility} order (0 = never changes, higher = changes more often) so the stable
 * prefix stays byte-identical and the most variable content lands last (prefix-cache friendly).
 */
public record PromptSegment(ChatMessage.Role role, String text, int volatility) {
}
