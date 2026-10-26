package ai.mobiuslab.workspace;

/** Which workspace a path is relative to. */
public enum WorkspaceArea {
    /** The calling agent's own workspace. */
    PRIVATE,
    /** The workspace shared by all agents (lock-protected). */
    SHARED
}
