package ai.mobiuslab.runtime;

import ai.mobiuslab.genome.Genome;

import java.time.Instant;

/**
 * Execution context shared by all nodes of one agent run.
 *
 * @param depth    sub-agent call depth (0 = top-level run)
 * @param deadline absolute deadline of the whole run
 */
public record NodeContext(String agentId, Genome genome, AgentInvocation invocation, String traceId, int depth,
                          Instant deadline) {
}
