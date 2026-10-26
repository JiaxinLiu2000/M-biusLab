package ai.mobiuslab.memory.store;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.memory.MemoryNote;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Metadata index of memory notes ({@code memory_note}); the body lives in the vault file. */
public interface MemoryNoteStore {

    /**
     * Stored row: {@code note.body()} holds only the cached excerpt.
     *
     * @param note         metadata (+ body excerpt)
     * @param filePath     vault-relative file path
     * @param indexPending true when the vector index still needs this row
     */
    record Row(MemoryNote note, String filePath, boolean indexPending) {
    }

    /** Inserts a row; returns false when the dedup key (owner, scope key, hash) already exists. */
    boolean insert(Row row, String contentHash, Actor actor);

    /** Row by id (non-deleted). */
    Optional<Row> find(String id);

    /** Rows by ids (non-deleted), in no particular order. */
    List<Row> findAll(Collection<String> ids);

    /** Id of an existing note with the same owner, scope key and content hash. */
    Optional<String> findDuplicate(String ownerAgentId, String scopeKey, String contentHash);

    /** Sets invalidated + reason + superseded_by. */
    void invalidate(String id, String reason, String supersededBy, Actor actor);

    /** Sets or clears the index-pending flag. */
    void setIndexPending(String id, boolean pending);

    /** Rows awaiting reindex, oldest first. */
    List<Row> pendingIndex(int limit);

    /** Keyword fallback candidates within allowed scope keys/kinds, newest first. */
    List<Row> textCandidates(List<String> tokens, List<String> allowedScopeKeys, List<String> kinds,
                             boolean includeInvalidated, int limit);

    /** Keyset page for the graph: rows with id &gt; {@code afterId}; filter keys scope/groupId/ownerAgentId. */
    List<Row> page(Map<String, String> filter, String afterId, int limit);
}
