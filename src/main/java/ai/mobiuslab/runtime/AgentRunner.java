package ai.mobiuslab.runtime;

/**
 * Executes an agent's active genome graph once. {@link #runGenome} lets the planned
 * keep-or-roll-back loop run a specific candidate genome.
 */
public interface AgentRunner {

    /** Runs the agent's currently active genome. */
    AgentRunResult run(String agentId, AgentInvocation invocation);

    /** Runs a specific genome version (e.g. an evolution candidate) without activating it. */
    AgentRunResult runGenome(String genomeId, AgentInvocation invocation);
}
