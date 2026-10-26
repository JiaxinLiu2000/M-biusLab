package ai.mobiuslab.prompt;

import java.time.Instant;
import java.util.List;

/**
 * An immutable, versioned prompt. New versions are created by proposals (reviewed by the safety
 * officer); old versions stay readable for traceability.
 *
 * @param key          stable prompt key, e.g. "master.planner.plan"
 * @param version      1-based version number
 * @param parentVersion version this one was derived from, null for v1
 * @param segments     ordered segments
 * @param variables    declared variable names
 * @param ownerAgentId agent that owns the prompt; null = platform prompt
 * @param invocations  how many times this version was rendered
 * @param createdBy    actor string
 * @param createdAt    creation time
 */
public record PromptTemplate(String key, int version, Integer parentVersion, List<PromptSegment> segments,
                             List<String> variables, String ownerAgentId, long invocations, String createdBy,
                             Instant createdAt) {
}
