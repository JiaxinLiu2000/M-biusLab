package ai.mobiuslab.runtime;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeService;
import ai.mobiuslab.genome.LifecycleState;
import ai.mobiuslab.monitor.AsyncMonitorBus;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.MonitorRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Plain-Java fakes shared by runtime/agent unit tests. */
public final class RuntimeFakes {

    private RuntimeFakes() {
    }

    /** Real monitor bus with persistence disabled. */
    public static MonitorBus monitor() {
        MonitorRepository repo = new MonitorRepository(null) {
            @Override
            public void insertBatch(List<MonitorEvent> events) {
            }
        };
        return new AsyncMonitorBus(repo, new MobiusProperties(null, ".", null, null, null, null,
                new MobiusProperties.Monitor(10_000, 100, Duration.ofSeconds(1), 1000), null));
    }

    /** In-memory genome service (no dedupe semantics beyond id). */
    public static final class FakeGenomes implements GenomeService {
        final Map<String, Genome> byId = new ConcurrentHashMap<>();
        final Map<String, String> active = new ConcurrentHashMap<>();

        /** Stores and activates a genome, assigning an id if needed. */
        public Genome put(Genome g) {
            Genome s = g.id() != null ? g : new Genome(Ids.newId("gen"), g.agentId(), "lin", g.parentId(), g.generation(),
                    g.role(), g.description(), g.models(), g.nodes(), g.edges(), g.trigger(), g.permissions(), g.memory(),
                    g.workspace(), g.params(), LifecycleState.ACTIVE);
            byId.put(s.id(), s);
            active.put(s.agentId(), s.id());
            return s;
        }

        @Override
        public Genome save(Genome draft, Actor actor, String reason) {
            for (Genome g : byId.values()) {
                if (g.agentId().equals(draft.agentId()) && g.contentHash().equals(draft.contentHash())) {
                    return g;
                }
            }
            Genome s = new Genome(Ids.newId("gen"), draft.agentId(), "lin", draft.parentId(), draft.generation(),
                    draft.role(), draft.description(), draft.models(), draft.nodes(), draft.edges(), draft.trigger(),
                    draft.permissions(), draft.memory(), draft.workspace(), draft.params(), LifecycleState.DRAFT);
            byId.put(s.id(), s);
            return s;
        }

        @Override
        public Genome get(String genomeId) {
            Genome g = byId.get(genomeId);
            if (g == null) {
                throw MobiusException.notFound("genome", genomeId);
            }
            return g;
        }

        @Override
        public Genome active(String agentId) {
            String id = active.get(agentId);
            if (id == null) {
                throw MobiusException.notFound("active genome", agentId);
            }
            return get(id);
        }

        @Override
        public void activate(String genomeId, Actor actor, String reason) {
            active.put(get(genomeId).agentId(), genomeId);
        }

        @Override
        public Genome rollback(String agentId, Actor actor, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setState(String genomeId, LifecycleState s, Actor actor, String reason) {
            byId.computeIfPresent(genomeId, (k, g) -> g.withState(s));
        }

        @Override
        public void recordScore(String genomeId, String objectiveId, double score) {
        }

        @Override
        public List<Genome> lineage(String lineageId) {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<Genome> history(String agentId) {
            return byId.values().stream().filter(g -> g.agentId().equals(agentId)).toList();
        }
    }

    /** In-memory run log mimicking the unique invocation id. */
    public static final class FakeRuns extends AgentRunRepository {
        final Map<String, StoredRun> rows = new ConcurrentHashMap<>();

        public FakeRuns() {
            super(null);
        }

        @Override
        public boolean start(String invocationId, String agentId, String genomeId, String traceId, Map<String, Object> input) {
            return rows.putIfAbsent(invocationId, new StoredRun("RUNNING", null)) == null;
        }

        @Override
        public void finish(AgentRunResult r, String traceId) {
            rows.put(r.invocationId(), new StoredRun(r.status().name(), r));
        }

        @Override
        public Optional<StoredRun> find(String invocationId) {
            return Optional.ofNullable(rows.get(invocationId));
        }
    }
}
