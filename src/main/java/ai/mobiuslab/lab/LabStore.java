package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of lab tools and the lab call log (JDBC in production, in-memory in tests). */
public interface LabStore {

    /** A persisted tool definition. {@code origin} is "builtin" or "agent". */
    record ToolRow(String id, String description, ConcurrencyPolicy policy, String origin, String ownerAgentId,
                   Map<String, Object> spec) {
    }

    /** One finished call. */
    record CallRow(String requestId, String toolId, String agentId, String operation, Map<String, Object> args,
                   LabResult result, long queuedMs, Instant createdAt) {
    }

    /** Result of an already finished call with this request id, if any. */
    Optional<LabResult> findFinished(String requestId);

    /** Persists a finished call; duplicates of the same request id are ignored. */
    void saveCall(CallRow call);

    /** Inserts or updates a tool definition. */
    void saveTool(ToolRow tool, Actor actor);

    /** All non-deleted agent-written tools (re-hydrated at startup). */
    List<ToolRow> agentTools();
}
