package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.audit.RevisionLog;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.prompt.PromptLibrary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL-backed {@link GenomeService}; the genome.state column is authoritative, bodies are immutable JSON. */
@Service
public class JdbcGenomeService implements GenomeService {

    private static final Logger log = LoggerFactory.getLogger(JdbcGenomeService.class);
    private static final String COLS = "id, state, body";
    private static final int MAX_LIST = 2000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RevisionLog revisions;
    private final MonitorBus monitor;
    private final ApplicationEventPublisher events;
    private final Cache<String, Genome> cache = Caffeine.newBuilder().maximumSize(5000)
            .expireAfterAccess(Duration.ofMinutes(30)).build();

    private final ObjectProvider<PromptLibrary> prompts;

    public JdbcGenomeService(JdbcClient jdbc, TransactionTemplate tx, RevisionLog revisions, MonitorBus monitor,
                             ApplicationEventPublisher events, ObjectProvider<PromptLibrary> prompts) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.revisions = revisions;
        this.monitor = monitor;
        this.events = events;
        this.prompts = prompts;
    }

    /** Copy of the genome with every unpinned prompted node pinned to the prompt's latest version (unknown keys stay null). */
    Genome pinPrompts(Genome g) {
        if (g.nodes() == null || g.nodes().stream().noneMatch(n -> n.promptKey() != null && n.promptVersion() == null)) {
            return g;
        }
        PromptLibrary lib = prompts.getIfAvailable();
        if (lib == null) {
            log.warn("genome {}: no PromptLibrary available, prompt versions left unpinned", g.id());
            return g;
        }
        List<NodeGene> pinned = g.nodes().stream().map(n -> {
            if (n.promptKey() == null || n.promptVersion() != null) {
                return n;
            }
            try {
                int v = lib.latest(n.promptKey()).version();
                return new NodeGene(n.geneId(), n.moduleId(), n.moduleVersion(), n.kind(), n.promptKey(), v, n.config());
            } catch (RuntimeException e) {
                log.warn("genome {} node {}: prompt {} not resolvable, version left unpinned: {}", g.id(), n.geneId(),
                        n.promptKey(), e.getMessage());
                return n;
            }
        }).toList();
        return new Genome(g.id(), g.agentId(), g.lineageId(), g.parentId(), g.generation(), g.role(), g.description(),
                g.models(), pinned, g.edges(), g.trigger(), g.permissions(), g.memory(), g.workspace(), g.params(),
                g.state());
    }

    /** {@inheritDoc} */
    @Override
    public Genome save(Genome input, Actor actor, String reason) {
        if (input == null || input.agentId() == null || input.role() == null) {
            throw MobiusException.invalid("genome needs agentId and role");
        }
        Genome draft = pinPrompts(input);
        String hash = draft.contentHash();
        Optional<String> existing = idByHash(draft.agentId(), hash);
        if (existing.isPresent()) {
            log.debug("genome dedupe: agent={} hash={} -> {}", draft.agentId(), hash, existing.get());
            return get(existing.get());
        }
        Genome parent = draft.parentId() == null ? null : get(draft.parentId());
        String lineage = draft.lineageId() != null ? draft.lineageId()
                : parent != null ? parent.lineageId() : Ids.newId("lin");
        int generation = parent != null && draft.generation() <= parent.generation() ? parent.generation() + 1
                : draft.generation();
        LifecycleState state = draft.state() == LifecycleState.ACTIVE ? LifecycleState.DRAFT : draft.state();
        Genome g = new Genome(draft.id() == null ? Ids.newId("gen") : draft.id(), draft.agentId(), lineage,
                draft.parentId(), generation, draft.role(), draft.description(), draft.models(), draft.nodes(),
                draft.edges(), draft.trigger(), draft.permissions(), draft.memory(), draft.workspace(),
                draft.params(), state);
        try {
            jdbc.sql("""
                            INSERT INTO genome (id, agent_id, lineage_id, parent_id, generation, role, state, content_hash,
                                                body, created_by, updated_by)
                            VALUES (:id, :agent, :lin, :parent, :gen, :role, :state, :hash, :body, :by, :by)""")
                    .param("id", g.id()).param("agent", g.agentId()).param("lin", g.lineageId())
                    .param("parent", g.parentId()).param("gen", g.generation()).param("role", g.role())
                    .param("state", g.state().name()).param("hash", hash).param("body", Json.write(g))
                    .param("by", actor.asString()).update();
        } catch (DuplicateKeyException e) {
            return idByHash(g.agentId(), hash).map(this::get)
                    .orElseThrow(() -> new MobiusException(ErrorCode.CONFLICT, "genome id already exists: " + g.id()));
        }
        revisions.record("genome", g.id(), "INSERT", actor, reason, null, g);
        cache.put(g.id(), g);
        stateEvent(g, "genome saved gen " + g.generation());
        log.info("genome saved id={} agent={} gen={}", g.id(), g.agentId(), g.generation());
        return g;
    }

    /** {@inheritDoc} */
    @Override
    public Genome get(String genomeId) {
        Genome g = cache.getIfPresent(genomeId);
        if (g != null) {
            return g;
        }
        g = jdbc.sql("SELECT " + COLS + " FROM genome WHERE id = :id AND deleted = 0").param("id", genomeId)
                .query(this::map).optional().orElseThrow(() -> MobiusException.notFound("genome", genomeId));
        cache.put(genomeId, g);
        return g;
    }

    /** {@inheritDoc} */
    @Override
    public Genome active(String agentId) {
        return activeId(agentId).map(this::get)
                .orElseThrow(() -> MobiusException.notFound("active genome of agent", agentId));
    }

    /** {@inheritDoc} */
    @Override
    public void activate(String genomeId, Actor actor, String reason) {
        Genome target = get(genomeId);
        tx.executeWithoutResult(s -> switchActive(target, LifecycleState.RETAINED, actor, reason));
        events.publishEvent(new GenomeActivatedEvent(target.agentId(), genomeId));
        log.info("genome activated id={} agent={}", genomeId, target.agentId());
    }

    /** {@inheritDoc} */
    @Override
    public Genome rollback(String agentId, Actor actor, String reason) {
        Genome current = active(agentId);
        if (current.parentId() == null) {
            throw MobiusException.invalid("genome " + current.id() + " has no parent to roll back to");
        }
        Genome parent = get(current.parentId());
        tx.executeWithoutResult(s -> switchActive(parent, LifecycleState.ROLLED_BACK, actor, reason));
        events.publishEvent(new GenomeActivatedEvent(agentId, parent.id()));
        log.info("genome rollback agent={} {} -> {}", agentId, current.id(), parent.id());
        return get(parent.id());
    }

    /** {@inheritDoc} */
    @Override
    public void setState(String genomeId, LifecycleState s, Actor actor, String reason) {
        if (s == LifecycleState.ACTIVE) {
            activate(genomeId, actor, reason);
            return;
        }
        Genome before = get(genomeId);
        tx.executeWithoutResult(st -> {
            updateState(before, s, actor, reason);
            if (before.state() == LifecycleState.ACTIVE) {
                jdbc.sql("""
                                UPDATE agent SET active_genome_id = NULL, updated_by = :by, row_version = row_version + 1
                                WHERE id = :a AND active_genome_id = :g""")
                        .param("by", actor.asString()).param("a", before.agentId()).param("g", genomeId).update();
            }
        });
        if (before.state() == LifecycleState.ACTIVE) {
            events.publishEvent(new GenomeActivatedEvent(before.agentId(), null));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void recordScore(String genomeId, String objectiveId, double score) {
        record Row(String summary, int version) {
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            Row row = jdbc.sql("SELECT score_summary, row_version FROM genome WHERE id = :id AND deleted = 0")
                    .param("id", genomeId).query((rs, i) -> new Row(rs.getString(1), rs.getInt(2))).optional()
                    .orElseThrow(() -> MobiusException.notFound("genome", genomeId));
            Map<String, Map<String, Object>> summary = row.summary() == null ? new LinkedHashMap<>()
                    : Json.read(row.summary(), new TypeReference<LinkedHashMap<String, Map<String, Object>>>() {
                    });
            Map<String, Object> before = summary.get(objectiveId);
            long count = before == null ? 0 : ((Number) before.get("count")).longValue();
            double avg = before == null ? 0 : ((Number) before.get("avg")).doubleValue();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("count", count + 1);
            entry.put("avg", (avg * count + score) / (count + 1));
            entry.put("last", score);
            summary.put(objectiveId, entry);
            int n = jdbc.sql("""
                            UPDATE genome SET score_summary = :s, row_version = row_version + 1, updated_by = 'SYSTEM:score'
                            WHERE id = :id AND row_version = :v""")
                    .param("s", Json.write(summary)).param("id", genomeId).param("v", row.version()).update();
            if (n == 1) {
                revisions.record("genome", genomeId, "SCORE", Actor.SYSTEM, objectiveId, before, entry);
                return;
            }
            log.debug("score update conflict on genome {}, retry {}", genomeId, attempt + 1);
        }
        throw new MobiusException(ErrorCode.CONFLICT, "concurrent score updates on genome " + genomeId, true, null);
    }

    /** Score summary of a genome: objectiveId -> {count, avg, last}; empty when never scored. */
    public Map<String, Object> scoreSummary(String genomeId) {
        String s = jdbc.sql("SELECT score_summary FROM genome WHERE id = :id").param("id", genomeId)
                .query(String.class).optional().orElse(null);
        return s == null ? Map.of() : Json.read(s, new TypeReference<Map<String, Object>>() {
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<Genome> lineage(String lineageId) {
        return jdbc.sql("SELECT " + COLS + " FROM genome WHERE lineage_id = :l AND deleted = 0 ORDER BY generation, created_at LIMIT " + MAX_LIST)
                .param("l", lineageId).query(this::map).list();
    }

    /** {@inheritDoc} */
    @Override
    public List<Genome> history(String agentId) {
        return jdbc.sql("SELECT " + COLS + " FROM genome WHERE agent_id = :a AND deleted = 0 ORDER BY generation, created_at LIMIT " + MAX_LIST)
                .param("a", agentId).query(this::map).list();
    }

    private void switchActive(Genome target, LifecycleState previousState, Actor actor, String reason) {
        Optional<String> current = activeId(target.agentId());
        if (current.isPresent() && !current.get().equals(target.id())) {
            updateState(get(current.get()), previousState, actor, reason);
        }
        Genome fresh = get(target.id());
        if (fresh.state() != LifecycleState.ACTIVE) {
            updateState(fresh, LifecycleState.ACTIVE, actor, reason);
        }
        jdbc.sql("UPDATE agent SET active_genome_id = :g, updated_by = :by, row_version = row_version + 1 WHERE id = :a")
                .param("g", target.id()).param("by", actor.asString()).param("a", target.agentId()).update();
    }

    private void updateState(Genome before, LifecycleState s, Actor actor, String reason) {
        jdbc.sql("UPDATE genome SET state = :s, updated_by = :by, row_version = row_version + 1 WHERE id = :id")
                .param("s", s.name()).param("by", actor.asString()).param("id", before.id()).update();
        cache.invalidate(before.id());
        revisions.record("genome", before.id(), "STATE", actor, reason, before.state().name(), s.name());
        stateEvent(before.withState(s), before.state() + " -> " + s);
    }

    private Optional<String> activeId(String agentId) {
        return jdbc.sql("SELECT id FROM genome WHERE agent_id = :a AND state = 'ACTIVE' AND deleted = 0 LIMIT 1")
                .param("a", agentId).query(String.class).optional();
    }

    private Optional<String> idByHash(String agentId, String hash) {
        return jdbc.sql("SELECT id FROM genome WHERE agent_id = :a AND content_hash = :h")
                .param("a", agentId).param("h", hash).query(String.class).optional();
    }

    private void stateEvent(Genome g, String summary) {
        monitor.emit(MonitorEvent.of(SourceKind.AGENT, g.agentId(), g.agentId(), EventType.STATE_CHANGE, summary)
                .withTarget(g.id()));
    }

    private Genome map(ResultSet rs, int i) throws SQLException {
        Genome body = Json.read(rs.getString("body"), Genome.class);
        return body.withState(LifecycleState.valueOf(rs.getString("state")));
    }
}
