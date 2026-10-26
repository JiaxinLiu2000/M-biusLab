package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;

import java.util.List;

/**
 * Registry and scheduler of lab tools. Enforces each tool's concurrency policy with fair queues,
 * applies timeouts, dedupes repeated request ids, checks agent permissions and reports TOOL_CALL
 * events. Registering agent-written tools requires a safety review (LAB_TOOL_REGISTER).
 */
public interface LabRegistry {

    /** Registers a tool (safety-reviewed when the actor is not SYSTEM). */
    void register(LabTool tool, Actor actor);

    /** Tools visible to agents. */
    List<LabTool> list();

    /** Invokes a tool as an agent, waiting in the tool's queue if necessary. */
    LabResult invoke(String toolId, LabRequest request);
}
