package ai.mobiuslab.runtime;

import java.util.Map;

/** Outcome of one agent run. */
public record AgentRunResult(String invocationId, String agentId, String genomeId, Status status,
                             Map<String, Object> output, double costUsd, long durationMs, String error) {

    /** Run status. */
    public enum Status { OK, FAILED, TIMEOUT, DENIED }

    /** True on success. */
    public boolean ok() {
        return status == Status.OK;
    }
}
