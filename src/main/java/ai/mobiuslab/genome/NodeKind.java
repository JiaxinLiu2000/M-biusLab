package ai.mobiuslab.genome;

/** Kinds of nodes an agent's internal graph can contain (AI nodes and pure-code nodes). */
public enum NodeKind {
    INPUT,
    OUTPUT,
    LLM,
    JEV_DECISION,
    CODE,
    TOOL,
    LAB,
    MEMORY_READ,
    MEMORY_WRITE,
    SUBAGENT_CALL
}
