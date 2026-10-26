package ai.mobiuslab.common;

import java.util.Objects;

/** Identifies who performed an action; stored in audit columns as {@code kind:id}. */
public record Actor(Kind kind, String id) {

    /** Category of the acting principal. */
    public enum Kind { USER, MASTER, AGENT, REFEREE, SYSTEM }

    public static final Actor SYSTEM = new Actor(Kind.SYSTEM, "system");
    public static final Actor USER = new Actor(Kind.USER, "user");
    public static final Actor REFEREE = new Actor(Kind.REFEREE, "referee");

    public Actor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** Creates an actor for a (sub-)agent id. */
    public static Actor agent(String agentId) {
        return new Actor(Kind.AGENT, agentId);
    }

    /** Creates an actor for a master-agent internal member (e.g. "master.planner"). */
    public static Actor master(String memberId) {
        return new Actor(Kind.MASTER, memberId);
    }

    /** Parses the {@code kind:id} form produced by {@link #asString()}. */
    public static Actor parse(String value) {
        int idx = value.indexOf(':');
        if (idx < 0) {
            return new Actor(Kind.SYSTEM, value);
        }
        return new Actor(Kind.valueOf(value.substring(0, idx)), value.substring(idx + 1));
    }

    /** Serialized form used in audit columns. */
    public String asString() {
        return kind.name() + ":" + id;
    }
}
