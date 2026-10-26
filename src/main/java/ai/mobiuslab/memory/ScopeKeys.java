package ai.mobiuslab.memory;

/**
 * Visibility keys used for filtering across stores: {@code PUBLIC}, {@code PRIVATE:<owner>},
 * {@code GROUP:<groupId>}. A caller's allowed keys are computed from {@link MemoryAccessPolicy}.
 */
public final class ScopeKeys {

    private ScopeKeys() {
    }

    /** Visibility key of a note. */
    public static String of(MemoryScope scope, String ownerAgentId, String groupId) {
        return switch (scope) {
            case PUBLIC -> "PUBLIC";
            case PRIVATE -> "PRIVATE:" + ownerAgentId;
            case GROUP -> "GROUP:" + groupId;
        };
    }

    /** Visibility key of a note. */
    public static String of(MemoryNote note) {
        return of(note.scope(), note.ownerAgentId(), note.groupId());
    }
}
