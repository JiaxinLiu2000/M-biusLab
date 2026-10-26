package ai.mobiuslab.memory;

/** A search result with its similarity score. */
public record MemoryHit(MemoryNote note, double score) {
}
