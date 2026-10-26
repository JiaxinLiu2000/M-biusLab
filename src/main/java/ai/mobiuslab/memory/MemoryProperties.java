package ai.mobiuslab.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory module settings ({@code mobius.memory.*}); defaults applied in code.
 *
 * @param collection        Qdrant collection name
 * @param cacheSize         max cached notes for {@code get}
 * @param reindexBatch      rows retried per reindex run
 * @param fallbackCandidates max rows scanned by the MySQL fallback search
 */
@ConfigurationProperties(prefix = "mobius.memory")
public record MemoryProperties(String collection, int cacheSize, int reindexBatch, int fallbackCandidates) {

    public MemoryProperties {
        collection = collection == null || collection.isBlank() ? "mobius_memory" : collection;
        cacheSize = cacheSize <= 0 ? 5_000 : cacheSize;
        reindexBatch = reindexBatch <= 0 ? 100 : reindexBatch;
        fallbackCandidates = fallbackCandidates <= 0 ? 500 : fallbackCandidates;
    }

    /** All defaults. */
    public static MemoryProperties defaults() {
        return new MemoryProperties(null, 0, 0, 0);
    }
}
