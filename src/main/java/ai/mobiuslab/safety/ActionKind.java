package ai.mobiuslab.safety;

/** Categories of actions that must pass the safety gate before execution. */
public enum ActionKind {
    SHELL_EXEC,
    CODE_EXEC,
    FILE_WRITE,
    FILE_DELETE,
    NETWORK,
    PROMPT_CHANGE,
    GENOME_CHANGE,
    AGENT_CREATE,
    AGENT_DELETE,
    PERMISSION_CHANGE,
    TOPOLOGY_CHANGE,
    FRONTEND_CHANGE,
    MEMORY_SHARED_WRITE,
    CRITERIA_CHANGE,
    LAB_TOOL_REGISTER
}
