package ai.mobiuslab.prompt;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.llm.ChatMessage;

import java.util.List;
import java.util.Map;

/** Central, versioned store of modular prompts (DB-backed, Caffeine-cached). */
public interface PromptLibrary {

    /** Latest active version of a prompt. */
    PromptTemplate latest(String key);

    /** A specific version. */
    PromptTemplate get(String key, int version);

    /**
     * Renders a version into chat messages (stable segments first, variables last) and increments its
     * invocation counter. Missing variables throw MobiusException(VALIDATION).
     */
    List<ChatMessage> render(String key, int version, Map<String, Object> variables);

    /**
     * Proposes a new version derived from {@code baseVersion}. The proposal is reviewed by the safety
     * gate (ActionKind.PROMPT_CHANGE); on ALLOW it is stored as the next version, otherwise
     * MobiusException(SAFETY_DENIED / NEEDS_USER_DECISION) is thrown.
     */
    PromptTemplate propose(String key, int baseVersion, List<PromptSegment> segments, Actor actor, String reason);

    /** All versions of a key, newest first. */
    List<PromptTemplate> history(String key);
}
