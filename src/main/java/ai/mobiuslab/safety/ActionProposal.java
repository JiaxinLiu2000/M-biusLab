package ai.mobiuslab.safety;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Ids;

import java.util.Map;
import java.util.Objects;

/**
 * Something an agent wants to do, submitted for review before it happens.
 *
 * @param id          proposal id (idempotency: re-reviewing the same id returns the stored verdict)
 * @param actor       who wants to do it
 * @param kind        action category
 * @param target      what it touches (path, agent id, prompt key, URL, ...)
 * @param description natural-language intent supplied by the actor
 * @param content     the concrete content (command, code, diff, new prompt text, ...)
 * @param attachments extra evidence, e.g. "screenshotPath" for FRONTEND_CHANGE
 */
public record ActionProposal(String id, Actor actor, ActionKind kind, String target, String description,
                             String content, Map<String, String> attachments) {

    public ActionProposal {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(kind, "kind");
        if (id == null) {
            id = Ids.newId("prp");
        }
        attachments = attachments == null ? Map.of() : Map.copyOf(attachments);
    }

    /** Proposal with a fresh id and no attachments. */
    public static ActionProposal of(Actor actor, ActionKind kind, String target, String description, String content) {
        return new ActionProposal(null, actor, kind, target, description, content, null);
    }
}
