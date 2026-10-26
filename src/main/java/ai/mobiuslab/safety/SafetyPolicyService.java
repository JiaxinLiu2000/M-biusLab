package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.safety.rules.RuleSet;

import java.time.Instant;

/** Live safety policy: the policy document, derived hard rules and user settings (hot reloaded). */
public interface SafetyPolicyService {

    /** A stored policy version. */
    record PolicyDocument(int version, String content, String sha256, Instant updatedAt, String updatedBy) {
    }

    /** Current policy document (re-read when the file on disk changes). */
    PolicyDocument policy();

    /** Replaces the policy text; stored as a new revision. Only the user may call this. */
    PolicyDocument update(String content, Actor actor, String reason);

    /** Effective hard rules: built-in rules overridden by the policy's embedded block. */
    RuleSet rules();

    /** Current settings. */
    SafetySettings settings();

    /** Applies non-null fields of {@code patch}; returns the new settings. */
    SafetySettings updateSettings(SafetySettings patch, Actor actor);
}
