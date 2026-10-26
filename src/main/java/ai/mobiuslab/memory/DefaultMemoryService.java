package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.memory.store.MemoryNoteStore;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyGate;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default {@link MemoryService}: permission-checked, append-only, monitored memory. Writes always go to
 * the Obsidian/MySQL/Qdrant system of record; agents routed to a custom backend additionally store there
 * and search through it. Thread-safe (concurrent registry, Caffeine cache, stateless collaborators).
 */
@Service
public class DefaultMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryService.class);
    private static final int GRAPH_PAGE = 1000;
    private static final int GRAPH_MAX = 20_000;

    private final ObsidianQdrantBackend obsidian;
    private final MemoryNoteStore store;
    private final MemoryAccessPolicy policy;
    private final MonitorBus monitor;
    private final Supplier<SafetyGate> safety;
    private final Function<String, String> backendForAgent;
    private final Map<String, MemoryBackend> backends = new ConcurrentHashMap<>();
    private final Cache<String, MemoryNote> cache;

    /** Spring constructor. */
    @Autowired
    public DefaultMemoryService(ObsidianQdrantBackend obsidian, EpisodicLogBackend episodic, MemoryNoteStore store,
                                MemoryAccessPolicy policy, MonitorBus monitor, MemoryProperties props,
                                ObjectProvider<SafetyGate> safety,
                                @Qualifier("memoryBackendResolver") ObjectProvider<Function<String, String>> resolver) {
        this(obsidian, episodic, store, policy, monitor, props, safety::getIfAvailable,
                agentId -> {
                    Function<String, String> f = resolver.getIfAvailable();
                    return f == null ? null : f.apply(agentId);
                });
    }

    /** Explicit constructor (tests). */
    public DefaultMemoryService(ObsidianQdrantBackend obsidian, EpisodicLogBackend episodic, MemoryNoteStore store,
                                MemoryAccessPolicy policy, MonitorBus monitor, MemoryProperties props,
                                Supplier<SafetyGate> safety, Function<String, String> backendForAgent) {
        this.obsidian = obsidian;
        this.store = store;
        this.policy = policy;
        this.monitor = monitor;
        this.safety = safety;
        this.backendForAgent = backendForAgent;
        this.cache = Caffeine.newBuilder().maximumSize(props.cacheSize()).build();
        backends.put(obsidian.id(), obsidian);
        if (episodic != null) {
            backends.put(episodic.id(), episodic);
        }
    }

    /** {@inheritDoc} */
    @Override
    public MemoryNote write(Actor actor, String agentId, MemoryWrite w) {
        validate(w);
        try (Span span = monitor.start(SourceKind.MEMORY, "memory", agentId, "write " + w.scope().key(w.groupId()))) {
            try {
                if (!policy.accessFor(agentId, w.scope(), w.groupId()).canWrite()) {
                    throw forbidden(agentId, "write", w.scope().key(w.groupId()));
                }
                MemoryNote note = new MemoryNote(Ids.newId("mem"), w.scope(), agentId,
                        w.scope() == MemoryScope.GROUP ? w.groupId() : null,
                        w.kind() == null ? "FACT" : w.kind(), w.title().strip(), w.body() == null ? "" : w.body(),
                        w.tags() == null ? List.of() : List.copyOf(w.tags()),
                        w.links() == null ? List.of() : List.copyOf(new LinkedHashSet<>(w.links())),
                        w.source(), clamp(w.confidence()), Instant.now(), false, null);
                MemoryNote stored = obsidian.persist(note, actor);
                MemoryBackend routed = routed(agentId);
                if (routed != obsidian && stored == note) {
                    routed.store(stored);
                }
                span.event(EventType.MEMORY_WRITE, stored.id(), (stored == note ? "wrote " : "dedup ") + stored.title(),
                        Json.write(Map.of("noteId", stored.id(), "scope", stored.scope().key(stored.groupId()),
                                "backend", routed.id(), "deduplicated", stored != note)));
                span.ok(stored.id());
                return stored;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryHit> search(String agentId, MemoryQuery query) {
        try (Span span = monitor.start(SourceKind.MEMORY, "memory", agentId, "search")) {
            try {
                List<String> keys = allowedKeys(agentId, query);
                MemoryBackend backend = routed(agentId);
                List<MemoryHit> hits = keys.isEmpty() ? List.of() : backend.find(query, keys);
                List<MemoryHit> out = new ArrayList<>();
                for (MemoryHit h : hits) {
                    MemoryNote n = h.note();
                    if (!keys.contains(ScopeKeys.of(n))) {
                        continue;
                    }
                    if (!query.includeInvalidated() && (n.invalidated()
                            || (backend != obsidian && store.find(n.id()).map(r -> r.note().invalidated()).orElse(false)))) {
                        continue;
                    }
                    out.add(h);
                }
                span.event(EventType.MEMORY_READ, null, "search hits=" + out.size(),
                        Json.write(Map.of("query", query.text() == null ? "" : query.text(), "backend", backend.id(),
                                "noteIds", out.stream().map(h -> h.note().id()).toList())));
                span.ok(out.size() + " hits");
                return out;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public MemoryNote get(String agentId, String noteId) {
        try (Span span = monitor.start(SourceKind.MEMORY, "memory", agentId, "get " + noteId)) {
            try {
                MemoryNote n = cache.get(noteId, id -> obsidian.load(id).orElse(null));
                if (n == null) {
                    throw MobiusException.notFound("memory note", noteId);
                }
                if (!canRead(agentId, n)) {
                    throw forbidden(agentId, "read", ScopeKeys.of(n));
                }
                span.event(EventType.MEMORY_READ, noteId, "read " + n.title(), Json.write(Map.of("noteIds", List.of(noteId))));
                span.ok(noteId);
                return n;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void invalidate(Actor actor, String agentId, String noteId, String reason, String supersededBy) {
        try (Span span = monitor.start(SourceKind.MEMORY, "memory", agentId, "invalidate " + noteId)) {
            try {
                MemoryNote n = obsidian.load(noteId).orElseThrow(() -> MobiusException.notFound("memory note", noteId));
                if (!canWrite(agentId, n)) {
                    throw forbidden(agentId, "invalidate", ScopeKeys.of(n));
                }
                if (!n.invalidated()) {
                    obsidian.invalidate(noteId, reason, supersededBy, actor);
                }
                cache.invalidate(noteId);
                span.event(EventType.MEMORY_WRITE, noteId, "invalidated: " + reason,
                        Json.write(Map.of("noteIds", List.of(noteId), "supersededBy", supersededBy == null ? "" : supersededBy)));
                span.ok(noteId);
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public GraphView graph(Map<String, String> filter) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphLink> links = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String after = "";
        while (nodes.size() < GRAPH_MAX) {
            List<MemoryNoteStore.Row> page = store.page(filter, after, GRAPH_PAGE);
            for (MemoryNoteStore.Row r : page) {
                MemoryNote n = r.note();
                nodes.add(new GraphNode(n.id(), n.title(), n.scope().name(), n.groupId(), n.ownerAgentId(), n.kind(),
                        n.invalidated()));
                ids.add(n.id());
                n.links().forEach(t -> links.add(new GraphLink(n.id(), t)));
                if (n.supersededBy() != null) {
                    links.add(new GraphLink(n.id(), n.supersededBy()));
                }
            }
            if (page.size() < GRAPH_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).note().id();
        }
        List<GraphLink> visible = links.stream().filter(l -> ids.contains(l.target())).distinct().toList();
        monitor.emit(ai.mobiuslab.monitor.MonitorEvent.of(SourceKind.MEMORY, "memory", null, EventType.MEMORY_READ,
                "graph nodes=" + nodes.size() + " links=" + visible.size()));
        return new GraphView(nodes, visible);
    }

    /** {@inheritDoc} */
    @Override
    public void registerBackend(MemoryBackend backend, Actor actor) {
        if (backend == null || backend.id() == null || backend.id().isBlank()) {
            throw MobiusException.invalid("Memory backend id required");
        }
        if (ObsidianQdrantBackend.ID.equals(backend.id())) {
            throw new MobiusException(ErrorCode.CONFLICT, "Cannot replace the default memory backend");
        }
        SafetyGate gate = safety.get();
        if (gate == null) {
            throw new MobiusException(ErrorCode.SAFETY_DENIED, "Safety gate unavailable; cannot register backend " + backend.id());
        }
        gate.require(ActionProposal.of(actor, ActionKind.GENOME_CHANGE, "memory-backend:" + backend.id(),
                "Register custom memory backend " + backend.id(), backend.getClass().getName()));
        MemoryBackend prev = backends.put(backend.id(), backend);
        log.info("Memory backend {} registered by {}{}", backend.id(), actor.asString(), prev != null ? " (replaced)" : "");
        monitor.emit(ai.mobiuslab.monitor.MonitorEvent.of(SourceKind.MEMORY, "memory", null, EventType.STATE_CHANGE,
                "backend registered: " + backend.id()).withTarget(backend.id()));
    }

    /** Ids of registered backends. */
    public Set<String> backendIds() {
        return Set.copyOf(backends.keySet());
    }

    /** True when the agent may write (and thus invalidate) the given note. */
    public boolean canWrite(String agentId, MemoryNote n) {
        if (n.scope() == MemoryScope.PRIVATE && !n.ownerAgentId().equals(agentId)) {
            return false;
        }
        return policy.accessFor(agentId, n.scope(), n.groupId()).canWrite();
    }

    private boolean canRead(String agentId, MemoryNote n) {
        if (n.scope() == MemoryScope.PRIVATE && !n.ownerAgentId().equals(agentId)) {
            return false;
        }
        return policy.accessFor(agentId, n.scope(), n.groupId()).canRead();
    }

    private List<String> allowedKeys(String agentId, MemoryQuery q) {
        List<MemoryScope> scopes = q.scopes() == null || q.scopes().isEmpty() ? List.of(MemoryScope.values()) : q.scopes();
        List<String> keys = new ArrayList<>();
        for (MemoryScope s : scopes) {
            if (s == MemoryScope.GROUP) {
                for (String g : q.groupIds() == null ? List.<String>of() : q.groupIds()) {
                    if (policy.accessFor(agentId, s, g).canRead()) {
                        keys.add(ScopeKeys.of(s, agentId, g));
                    }
                }
            } else if (policy.accessFor(agentId, s, null).canRead()) {
                keys.add(ScopeKeys.of(s, agentId, null));
            }
        }
        return keys;
    }

    private MemoryBackend routed(String agentId) {
        String id = backendForAgent == null ? null : backendForAgent.apply(agentId);
        if (id == null) {
            return obsidian;
        }
        MemoryBackend b = backends.get(id);
        if (b == null) {
            log.warn("Agent {} requests unknown memory backend {}; using obsidian", agentId, id);
            return obsidian;
        }
        return b;
    }

    private static void validate(MemoryWrite w) {
        if (w == null || w.scope() == null || w.title() == null || w.title().isBlank()) {
            throw MobiusException.invalid("Memory write needs scope and title");
        }
        if (w.scope() == MemoryScope.GROUP && (w.groupId() == null || w.groupId().isBlank())) {
            throw MobiusException.invalid("GROUP memory write needs groupId");
        }
    }

    private static double clamp(double c) {
        return Double.isNaN(c) ? 0.5 : Math.max(0, Math.min(1, c));
    }

    private static MobiusException forbidden(String agentId, String op, String key) {
        return new MobiusException(ErrorCode.FORBIDDEN, "Agent " + agentId + " may not " + op + " memory " + key);
    }
}
