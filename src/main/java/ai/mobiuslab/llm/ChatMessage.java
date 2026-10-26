package ai.mobiuslab.llm;

import java.util.Objects;

/** One chat message. Crusoe rejects the "developer" role, so only these three roles exist. */
public record ChatMessage(Role role, String content) {

    /** Message author role. */
    public enum Role { SYSTEM, USER, ASSISTANT }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    /** System message. */
    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text);
    }

    /** User message. */
    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    /** Assistant message. */
    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
