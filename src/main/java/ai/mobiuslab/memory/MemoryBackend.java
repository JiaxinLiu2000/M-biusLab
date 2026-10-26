package ai.mobiuslab.memory;

import java.util.List;

/**
 * Pluggable memory system. Agents can evolve new backends (e.g. key-value journal, episodic log)
 * and assign them via MemorySpec.backend; different agents may use entirely different backends.
 */
public interface MemoryBackend {

    /** Backend id referenced by MemorySpec.backend. */
    String id();

    /** Stores a note already permission-checked by MemoryService. */
    void store(MemoryNote note);

    /** Finds candidate notes for a query (already scope-filtered by the caller's permissions). */
    List<MemoryHit> find(MemoryQuery query, List<String> allowedScopeKeys);
}
