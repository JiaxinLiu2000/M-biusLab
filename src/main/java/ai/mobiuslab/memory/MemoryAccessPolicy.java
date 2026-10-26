package ai.mobiuslab.memory;

import ai.mobiuslab.genome.Access;

/**
 * Resolves what memory access an agent has (implemented from the agent's active genome permissions).
 * The memory module asks this before every read/write.
 */
public interface MemoryAccessPolicy {

    /** Access of {@code agentId} to a scope ({@code groupId} only for GROUP). */
    Access accessFor(String agentId, MemoryScope scope, String groupId);
}
