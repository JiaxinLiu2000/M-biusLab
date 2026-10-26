package ai.mobiuslab.runtime;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeService;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Default {@link AgentRunner}: idempotent on invocationId (agent_run unique key; finished rows are returned,
 * concurrent duplicates wait for the original), one AGENT span per run, and every exception mapped to a
 * FAILED / TIMEOUT / DENIED result instead of being thrown.
 */
@Service
public class DefaultAgentRunner implements AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRunner.class);
    private static final Map<String, Object> SEALED_MARKER = Map.of("sealed", true);

    private final GenomeService genomes;
    private final GraphExecutor executor;
    private final AgentRunRepository runs;
    private final MonitorBus monitor;
    private final RuntimeProperties props;
    private final ConcurrentHashMap<String, CompletableFuture<AgentRunResult>> inflight = new ConcurrentHashMap<>();

    public DefaultAgentRunner(GenomeService genomes, GraphExecutor executor, AgentRunRepository runs, MonitorBus monitor,
                              RuntimeProperties props) {
        this.genomes = genomes;
        this.executor = executor;
        this.runs = runs;
        this.monitor = monitor;
        this.props = props;
    }

    /** {@inheritDoc} */
    @Override
    public AgentRunResult run(String agentId, AgentInvocation invocation) {
        return runOnce(agentId, invocation, () -> genomes.active(agentId));
    }

    /** {@inheritDoc} */
    @Override
    public AgentRunResult runGenome(String genomeId, AgentInvocation invocation) {
        Genome g;
        try {
            g = genomes.get(genomeId);
        } catch (RuntimeException e) {
            return new AgentRunResult(invocation.invocationId(), null, genomeId, AgentRunResult.Status.FAILED, Map.of(),
                    0, 0, e.getMessage());
        }
        return runOnce(g.agentId(), invocation, () -> g);
    }

    private AgentRunResult runOnce(String agentId, AgentInvocation inv, Supplier<Genome> genome) {
        CompletableFuture<AgentRunResult> mine = new CompletableFuture<>();
        CompletableFuture<AgentRunResult> other = inflight.putIfAbsent(inv.invocationId(), mine);
        if (other != null) {
            log.debug("invocation {} already running in-process; waiting", inv.invocationId());
            return other.join();
        }
        try {
            AgentRunResult r = claimAndRun(agentId, inv, genome);
            mine.complete(r);
            return r;
        } catch (RuntimeException e) {
            AgentRunResult r = new AgentRunResult(inv.invocationId(), agentId, null, AgentRunResult.Status.FAILED,
                    Map.of(), 0, 0, e.getMessage());
            mine.complete(r);
            return r;
        } finally {
            inflight.remove(inv.invocationId(), mine);
        }
    }

    private AgentRunResult claimAndRun(String agentId, AgentInvocation inv, Supplier<Genome> genomeSupplier) {
        var stored = runs.find(inv.invocationId());
        if (stored.isPresent()) {
            return stored.get().result() != null ? stored.get().result() : awaitOther(inv.invocationId(), agentId);
        }
        Genome genome = null;
        String genomeError = null;
        try {
            genome = genomeSupplier.get();
        } catch (RuntimeException e) {
            genomeError = e.getMessage();
        }
        Map<String, Object> persistedInput = inv.sealed() ? SEALED_MARKER : inv.input();
        if (!runs.start(inv.invocationId(), agentId, genome == null ? null : genome.id(), inv.traceId(), persistedInput)) {
            return awaitOther(inv.invocationId(), agentId);
        }
        AgentRunResult result;
        String traceId = inv.traceId();
        long t0 = System.nanoTime();
        try (Span span = monitor.start(SourceKind.AGENT, agentId, agentId, "run " + inv.invocationId())) {
            traceId = traceId == null ? span.traceId() : traceId;
            if (genome == null) {
                result = new AgentRunResult(inv.invocationId(), agentId, null, AgentRunResult.Status.FAILED, Map.of(), 0,
                        0, genomeError);
                span.fail(new MobiusException(ErrorCode.NOT_FOUND, genomeError));
            } else {
                result = execute(agentId, genome, inv, traceId, span, t0);
            }
        }
        runs.finish(inv.sealed() ? new AgentRunResult(result.invocationId(), result.agentId(), result.genomeId(),
                result.status(), SEALED_MARKER, result.costUsd(), result.durationMs(), result.error()) : result, traceId);
        log.info("agent run {} agent={} status={} {}ms ${}", inv.invocationId(), agentId, result.status(),
                result.durationMs(), result.costUsd());
        return result;
    }

    private AgentRunResult execute(String agentId, Genome genome, AgentInvocation inv, String traceId, Span span, long t0) {
        int depth = 0; // single-agent runtime: no nested sub-agent calls yet
        NodeContext ctx = new NodeContext(agentId, genome, inv, traceId, depth, Instant.now().plus(props.runTimeout()));
        try {
            GraphExecutor.GraphResult g = executor.execute(ctx);
            span.ok(inv.sealed() ? "sealed" : "ok, nodes=" + g.executed().size() + " skipped=" + g.skipped().size());
            return new AgentRunResult(inv.invocationId(), agentId, genome.id(), AgentRunResult.Status.OK, g.output(),
                    g.costUsd(), elapsed(t0), null);
        } catch (RuntimeException e) {
            span.fail(e);
            AgentRunResult.Status status = statusOf(e);
            log.warn("agent run {} agent={} {}: {}", inv.invocationId(), agentId, status, e.getMessage());
            return new AgentRunResult(inv.invocationId(), agentId, genome.id(), status, Map.of(), 0, elapsed(t0),
                    e.getMessage());
        }
    }

    private static AgentRunResult.Status statusOf(RuntimeException e) {
        if (e instanceof MobiusException se) {
            switch (se.code()) {
                case FORBIDDEN, SAFETY_DENIED, NEEDS_USER_DECISION -> {
                    return AgentRunResult.Status.DENIED;
                }
                case TIMEOUT -> {
                    return e instanceof GraphExecutor.NodeFailedException ? AgentRunResult.Status.FAILED
                            : AgentRunResult.Status.TIMEOUT;
                }
                default -> {
                    return AgentRunResult.Status.FAILED;
                }
            }
        }
        return AgentRunResult.Status.FAILED;
    }

    private AgentRunResult awaitOther(String invocationId, String agentId) {
        long until = System.nanoTime() + props.duplicateWait().toNanos();
        while (System.nanoTime() < until) {
            var stored = runs.find(invocationId);
            if (stored.isPresent() && stored.get().result() != null) {
                return stored.get().result();
            }
            try {
                Thread.sleep(props.duplicatePollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new AgentRunResult(invocationId, agentId, null, AgentRunResult.Status.TIMEOUT, Map.of(), 0, 0,
                "timed out waiting for duplicate invocation " + invocationId);
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
