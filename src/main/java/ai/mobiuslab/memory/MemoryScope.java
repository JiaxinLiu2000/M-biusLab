package ai.mobiuslab.memory;

/** Visibility of a memory note: owner only, a named group, or every agent. */
public enum MemoryScope {
    PRIVATE,
    GROUP,
    PUBLIC;

    /** Permission key as used in PermissionSet.memory ("PRIVATE", "PUBLIC", "GROUP:&lt;id&gt;"). */
    public String key(String groupId) {
        return this == GROUP ? "GROUP:" + groupId : name();
    }
}
