package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.memory.index.QdrantIndex;
import ai.mobiuslab.memory.store.MemoryNoteStore;
import ai.mobiuslab.memory.store.MemoryNoteStore.Row;
import ai.mobiuslab.memory.vault.ObsidianVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Default memory backend "obsidian": Obsidian vault file (source of truth for the body) + MySQL
 * metadata row + Qdrant vector. Falls back to MySQL keyword search when Qdrant is degraded and marks
 * rows {@code index_pending} for the reindexer. Thread-safe: all state lives in the collaborators.
 */
@Component
public class ObsidianQdrantBackend implements MemoryBackend {

    /** Backend id. */
    public static final String ID = "obsidian";

    private static final Logger log = LoggerFactory.getLogger(ObsidianQdrantBackend.class);

    private final ObsidianVault vault;
    private final MemoryNoteStore store;
    private final QdrantIndex index;
    private final Embedder embedder;
    private final MemoryProperties props;

    /** Creates the backend. */
    public ObsidianQdrantBackend(ObsidianVault vault, MemoryNoteStore store, QdrantIndex index, Embedder embedder,
                                 MemoryProperties props) {
        this.vault = vault;
        this.store = store;
        this.index = index;
        this.embedder = embedder;
        this.props = props;
    }

    /** {@inheritDoc} */
    @Override
    public String id() {
        return ID;
    }

    /** Content hash used for idempotent writes. */
    public static String contentHash(MemoryNote n) {
        return Json.sha256Hex(n.kind() + "\n" + n.title() + "\n" + (n.body() == null ? "" : n.body()));
    }

    /** {@inheritDoc} */
    @Override
    public void store(MemoryNote note) {
        persist(note, Actor.agent(note.ownerAgentId()));
    }

    /** Persists file + row + vector; returns the existing note when the same content already exists. */
    public MemoryNote persist(MemoryNote note, Actor actor) {
        String hash = contentHash(note);
        String key = ScopeKeys.of(note);
        Optional<String> dup = store.findDuplicate(note.ownerAgentId(), key, hash);
        if (dup.isPresent()) {
            return load(dup.get()).orElseThrow();
        }
        String path = vault.write(note, this::linkStem);
        if (!store.insert(new Row(note, path, true), hash, actor)) {
            String existing = store.findDuplicate(note.ownerAgentId(), key, hash).orElseThrow();
            log.debug("Concurrent duplicate write of {} resolved to {}", note.id(), existing);
            return load(existing).orElseThrow();
        }
        indexOrMarkPending(note);
        return note;
    }

    /** Marks a note invalid in DB, vault frontmatter and the vector payload. */
    public MemoryNote invalidate(String noteId, String reason, String supersededBy, Actor actor) {
        MemoryNote cur = load(noteId).orElseThrow(() -> MobiusException.notFound("memory note", noteId));
        store.invalidate(noteId, reason, supersededBy, actor);
        MemoryNote next = new MemoryNote(cur.id(), cur.scope(), cur.ownerAgentId(), cur.groupId(), cur.kind(),
                cur.title(), cur.body(), cur.tags(), cur.links(), cur.source(), cur.confidence(), cur.createdAt(),
                true, supersededBy);
        vault.write(next, this::linkStem);
        if (!index.ready()) {
            store.setIndexPending(noteId, true);
            return next;
        }
        try {
            index.markInvalidated(noteId, supersededBy);
        } catch (RuntimeException e) {
            log.warn("Qdrant payload update failed for {}; queued for reindex: {}", noteId, e.getMessage());
            index.markDegraded();
            store.setIndexPending(noteId, true);
        }
        return next;
    }

    /** Full note (body from the vault file; DB excerpt if the file is unreadable). */
    public Optional<MemoryNote> load(String noteId) {
        return store.find(noteId).map(this::full);
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryHit> find(MemoryQuery query, List<String> allowedScopeKeys) {
        int limit = query.limit() <= 0 ? 10 : query.limit();
        String text = query.text() == null ? "" : query.text();
        if (index.ready()) {
            try {
                List<QdrantIndex.Hit> hits = index.search(text, allowedScopeKeys, query.kinds(), query.includeInvalidated(), limit);
                Map<String, Row> rows = new LinkedHashMap<>();
                store.findAll(hits.stream().map(QdrantIndex.Hit::noteId).toList()).forEach(r -> rows.put(r.note().id(), r));
                List<MemoryHit> out = new ArrayList<>();
                for (QdrantIndex.Hit h : hits) {
                    Row r = rows.get(h.noteId());
                    if (r != null && allowedScopeKeys.contains(ScopeKeys.of(r.note()))
                            && (query.includeInvalidated() || !r.note().invalidated())) {
                        out.add(new MemoryHit(full(r), h.score()));
                    }
                }
                return out;
            } catch (RuntimeException e) {
                log.warn("Qdrant search failed, using MySQL fallback: {}", e.getMessage());
                index.markDegraded();
            }
        }
        return fallback(text, query, allowedScopeKeys, limit);
    }

    private List<MemoryHit> fallback(String text, MemoryQuery q, List<String> keys, int limit) {
        List<String> tokens = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isBlank()).toList();
        List<Row> rows = store.textCandidates(tokens, keys, q.kinds(), q.includeInvalidated(), props.fallbackCandidates());
        float[] qv = embedder.embed(text);
        return rows.stream()
                .map(r -> new MemoryHit(r.note(), Embedder.cosine(qv,
                        embedder.embed(r.note().title() + "\n" + (r.note().body() == null ? "" : r.note().body())))))
                .sorted(Comparator.comparingDouble(MemoryHit::score).reversed())
                .limit(limit)
                .map(h -> new MemoryHit(full(rowOf(h.note(), rows)), h.score()))
                .toList();
    }

    /** Retries indexing of pending rows; returns how many were indexed. */
    public int reindexPending() {
        if (!index.ready() && !index.ensureCollection()) {
            return 0;
        }
        int done = 0;
        for (Row r : store.pendingIndex(props.reindexBatch())) {
            try {
                index.upsert(full(r));
                store.setIndexPending(r.note().id(), false);
                done++;
            } catch (RuntimeException e) {
                log.warn("Reindex of {} failed: {}", r.note().id(), e.getMessage());
                index.markDegraded();
                break;
            }
        }
        if (done > 0) {
            log.info("Reindexed {} memory notes into Qdrant", done);
        }
        return done;
    }

    /** Wikilink stem of a linked note id (the id itself when unknown). */
    public String linkStem(String noteId) {
        return store.find(noteId).map(r -> ObsidianVault.stem(r.note().title(), noteId)).orElse(noteId);
    }

    /** Link-stem resolver usable by the vault. */
    public Function<String, String> linkStems() {
        return this::linkStem;
    }

    private void indexOrMarkPending(MemoryNote note) {
        try {
            index.upsert(note);
            store.setIndexPending(note.id(), false);
        } catch (RuntimeException e) {
            log.debug("Qdrant upsert deferred for {}: {}", note.id(), e.getMessage());
            index.markDegraded();
        }
    }

    private static Row rowOf(MemoryNote n, List<Row> rows) {
        return rows.stream().filter(r -> r.note().id().equals(n.id())).findFirst().orElseThrow();
    }

    private MemoryNote full(Row r) {
        try {
            MemoryNote f = vault.read(r.filePath());
            MemoryNote m = r.note();
            return new MemoryNote(m.id(), m.scope(), m.ownerAgentId(), m.groupId(), m.kind(), m.title(), f.body(),
                    m.tags(), m.links(), m.source(), m.confidence(), m.createdAt(), m.invalidated(), m.supersededBy());
        } catch (RuntimeException e) {
            log.warn("Vault file missing for {} ({}); using DB excerpt", r.note().id(), e.getMessage());
            return r.note();
        }
    }
}
