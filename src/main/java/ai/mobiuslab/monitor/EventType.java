package ai.mobiuslab.monitor;

/** Kinds of monitor events. Every component reports through these. */
public enum EventType {
    TASK_START,
    TASK_END,
    TASK_FAIL,
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    DATA_FLOW,
    TOOL_CALL,
    MEMORY_READ,
    MEMORY_WRITE,
    LLM_CALL,
    DECISION,
    STATE_CHANGE,
    SAFETY_VERDICT,
    EVOLUTION,
    USER_ESCALATION,
    LOG
}
