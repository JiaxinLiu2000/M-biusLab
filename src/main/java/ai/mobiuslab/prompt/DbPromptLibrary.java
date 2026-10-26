package ai.mobiuslab.prompt;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyGate;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DB-backed {@link PromptLibrary}: immutable versions in {@code prompt_template}, Caffeine-cached reads,
 * lock-free invocation counters flushed every 5 s, safety-reviewed proposals.
 */
@Service
public class DbPromptLibrary implements PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(DbPromptLibrary.class);
    private static final String ENTITY = "prompt_template";
    private static final String SOURCE = "prompt.library";

    private final PromptRepository repository;
    private final RevisionLog revisions;
    private final ObjectProvider<SafetyGate> safety;
    private final MonitorBus monitor;
    private final TransactionTemplate tx;
    private final InvocationCounter counter = new InvocationCounter();
    private final Cache<InvocationCounter.Ref, PromptTemplate> versions = Caffeine.newBuilder()
            .maximumSize(2000).expireAfterAccess(Duration.ofMinutes(30)).build();
    private final Cache<String, Integer> latestVersion = Caffeine.newBuilder()
            .maximumSize(2000).expireAfterWrite(Duration.ofMinutes(5)).build();

    /** Wires the library. */
    public DbPromptLibrary(PromptRepository repository, RevisionLog revisions, ObjectProvider<SafetyGate> safety,
                           MonitorBus monitor, TransactionTemplate tx) {
        this.repository = repository;
        this.revisions = revisions;
        this.safety = safety;
        this.monitor = monitor;
        this.tx = tx;
    }

    @Override
    public PromptTemplate latest(String key) {
        Integer v = latestVersion.get(key, k -> repository.latest(k).map(r -> {
            versions.put(new InvocationCounter.Ref(k, r.template().version()), r.template());
            return r.template().version();
        }).orElse(null));
        if (v == null) {
            throw MobiusException.notFound("prompt", key);
        }
        return get(key, v);
    }

    @Override
    public PromptTemplate get(String key, int version) {
        PromptTemplate t = versions.get(new InvocationCounter.Ref(key, version),
                ref -> repository.find(ref.key(), ref.version()).map(PromptRepository.Row::template).orElse(null));
        if (t == null) {
            throw MobiusException.notFound("prompt", key + "@v" + version);
        }
        return withPending(t);
    }

    private PromptTemplate withPending(PromptTemplate t) {
        long pending = counter.pending(t.key(), t.version());
        return pending == 0 ? t : new PromptTemplate(t.key(), t.version(), t.parentVersion(), t.segments(),
                t.variables(), t.ownerAgentId(), t.invocations() + pending, t.createdBy(), t.createdAt());
    }

    @Override
    public List<ChatMessage> render(String key, int version, Map<String, Object> variables) {
        PromptTemplate t = get(key, version);
        List<ChatMessage> messages = PromptRenderer.render(key, t.segments(), variables);
        counter.increment(key, version);
        monitor.emit(MonitorEvent.of(SourceKind.MODULE, SOURCE, t.ownerAgentId(), EventType.LOG,
                "render " + key + "@v" + version).withTarget(key));
        return messages;
    }

    @Override
    public PromptTemplate propose(String key, int baseVersion, List<PromptSegment> segments, Actor actor, String reason) {
        if (segments == null || segments.isEmpty()) {
            throw MobiusException.invalid("prompt proposal for '" + key + "' has no segments");
        }
        PromptTemplate base = get(key, baseVersion);
        try (Span span = monitor.start(SourceKind.MODULE, SOURCE, actor.kind() == Actor.Kind.AGENT ? actor.id() : null,
                "propose " + key + " from v" + baseVersion)) {
            try {
                safety.getObject().require(new ActionProposal(null, actor, ActionKind.PROMPT_CHANGE, key,
                        reason, Json.write(segments), Map.of("baseVersion", String.valueOf(baseVersion))));
                PromptTemplate created = tx.execute(status -> {
                    PromptRepository.Row latest = repository.latest(key)
                            .orElseThrow(() -> MobiusException.notFound("prompt", key));
                    int next = latest.template().version() + 1;
                    if (!repository.supersede(key, latest.template().version(), latest.rowVersion(), actor)) {
                        throw new MobiusException(ErrorCode.CONFLICT, "prompt '" + key + "' changed concurrently; retry");
                    }
                    PromptTemplate t = new PromptTemplate(key, next, baseVersion, List.copyOf(segments),
                            PromptRenderer.variables(segments), base.ownerAgentId(), 0, actor.asString(), Instant.now());
                    try {
                        repository.insert(t, actor);
                    } catch (DuplicateKeyException e) {
                        throw new MobiusException(ErrorCode.CONFLICT, "prompt '" + key + "' v" + next + " already exists", e);
                    }
                    revisions.record(ENTITY, key + "@v" + next, "CREATE", actor, reason, base, t);
                    return t;
                });
                latestVersion.invalidate(key);
                span.event(EventType.STATE_CHANGE, key, "prompt " + key + " -> v" + created.version(), null);
                span.ok("v" + created.version());
                log.info("prompt {} v{} created from v{} by {}", key, created.version(), baseVersion, actor.asString());
                return created;
            } catch (RuntimeException e) {
                span.fail(e);
                throw e;
            }
        }
    }

    @Override
    public List<PromptTemplate> history(String key) {
        return repository.history(key).stream().map(this::withPending).toList();
    }

    /** Imports a seed template as version 1 when the key does not exist; returns true if inserted. */
    public boolean importSeed(String key, List<PromptSegment> segments, String ownerAgentId) {
        if (repository.exists(key)) {
            return false;
        }
        PromptTemplate t = new PromptTemplate(key, 1, null, List.copyOf(segments), PromptRenderer.variables(segments),
                ownerAgentId, 0, Actor.SYSTEM.asString(), Instant.now());
        try {
            repository.insert(t, Actor.SYSTEM);
        } catch (DuplicateKeyException e) {
            return false;
        }
        revisions.record(ENTITY, key + "@v1", "CREATE", Actor.SYSTEM, "seed import", null, t);
        latestVersion.invalidate(key);
        return true;
    }

    /** Flushes pending invocation counters to the DB (every 5 s). */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void flushInvocations() {
        Map<InvocationCounter.Ref, Long> deltas = counter.drain();
        if (deltas.isEmpty()) {
            return;
        }
        try {
            repository.addInvocations(deltas);
            deltas.keySet().forEach(versions::invalidate);
            log.debug("flushed invocation counters for {} prompt versions", deltas.size());
        } catch (RuntimeException e) {
            counter.restore(deltas);
            log.warn("prompt invocation flush failed, will retry: {}", e.getMessage());
        }
    }

    /** Final flush on shutdown. */
    @PreDestroy
    public void shutdown() {
        flushInvocations();
    }
}
