package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.memory.store.MemoryNoteStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link MemoryNoteStore} for unit tests. */
class InMemoryNoteStore implements MemoryNoteStore {

    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private final Map<String, String> dedup = new ConcurrentHashMap<>();

    @Override
    public boolean insert(Row row, String contentHash, Actor actor) {
        MemoryNote n = row.note();
        String key = n.ownerAgentId() + "|" + ScopeKeys.of(n) + "|" + contentHash;
        if (dedup.putIfAbsent(key, n.id()) != null) {
            return false;
        }
        rows.put(n.id(), row);
        return true;
    }

    @Override
    public Optional<Row> find(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<Row> findAll(Collection<String> ids) {
        return ids.stream().map(rows::get).filter(r -> r != null).toList();
    }

    @Override
    public Optional<String> findDuplicate(String ownerAgentId, String scopeKey, String contentHash) {
        return Optional.ofNullable(dedup.get(ownerAgentId + "|" + scopeKey + "|" + contentHash));
    }

    @Override
    public void invalidate(String id, String reason, String supersededBy, Actor actor) {
        rows.computeIfPresent(id, (k, r) -> {
            MemoryNote m = r.note();
            return new Row(new MemoryNote(m.id(), m.scope(), m.ownerAgentId(), m.groupId(), m.kind(), m.title(), m.body(),
                    m.tags(), m.links(), m.source(), m.confidence(), m.createdAt(), true, supersededBy), r.filePath(), true);
        });
    }

    @Override
    public void setIndexPending(String id, boolean pending) {
        rows.computeIfPresent(id, (k, r) -> new Row(r.note(), r.filePath(), pending));
    }

    @Override
    public List<Row> pendingIndex(int limit) {
        return rows.values().stream().filter(Row::indexPending).limit(limit).toList();
    }

    @Override
    public List<Row> textCandidates(List<String> tokens, List<String> keys, List<String> kinds, boolean includeInvalidated,
                                    int limit) {
        return rows.values().stream()
                .filter(r -> keys.contains(ScopeKeys.of(r.note())))
                .filter(r -> includeInvalidated || !r.note().invalidated())
                .filter(r -> kinds == null || kinds.isEmpty() || kinds.contains(r.note().kind()))
                .limit(limit).toList();
    }

    @Override
    public List<Row> page(Map<String, String> filter, String afterId, int limit) {
        List<Row> out = new ArrayList<>(rows.values().stream()
                .filter(r -> r.note().id().compareTo(afterId == null ? "" : afterId) > 0)
                .filter(r -> filter == null || filter.get("scope") == null || r.note().scope().name().equals(filter.get("scope")))
                .toList());
        out.sort(Comparator.comparing(r -> r.note().id()));
        return out.subList(0, Math.min(limit, out.size()));
    }
}
