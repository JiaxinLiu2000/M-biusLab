package ai.mobiuslab.monitor;

/** Category of component that emitted a monitor event. */
public enum SourceKind {
    AGENT,
    MASTER,
    NODE,
    MODULE,
    TOOL,
    LLM,
    MEMORY,
    WORKSPACE,
    LAB,
    TOPOLOGY,
    SAFETY,
    REFEREE,
    EVOLUTION,
    TASKS,
    USER,
    SYSTEM
}
