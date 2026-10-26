package ai.mobiuslab.runtime;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.EdgeGene;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a genome's node graph: validates it is a DAG, runs nodes in topological order, evaluates
 * conditional edges ({@code key == value} / {@code key != value}) on the source output, skips nodes with no
 * active incoming edge (and so their exclusive descendants), applies per-node timeouts on virtual threads
 * and the per-node failure policy (config.onError = fail | skip | fallback with config.fallback).
 */
@Component
public class GraphExecutor {

    /** Outcome of a graph run: final output, total cost and the gene ids that executed / were skipped. */
    public record GraphResult(Map<String, Object> output, double costUsd, List<String> executed, List<String> skipped) {
    }

    /** A node failed under the "fail" policy; the message contains the gene id. */
    public static final class NodeFailedException extends MobiusException {
        private final String geneId;

        NodeFailedException(String geneId, ErrorCode code, String message, Throwable cause) {
            super(code, "node " + geneId + " failed: " + message, false, cause);
            this.geneId = geneId;
        }

        /** Failing node gene id. */
        public String geneId() {
            return geneId;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GraphExecutor.class);
    private static final long DEFAULT_TIMEOUT_MS = 120_000;

    private final Map<NodeKind, Node> nodes = new EnumMap<>(NodeKind.class);
    private final MonitorBus monitor;

    public GraphExecutor(List<Node> implementations, MonitorBus monitor) {
        for (Node n : implementations) {
            if (nodes.put(n.kind(), n) != null) {
                throw new IllegalStateException("duplicate node implementation for " + n.kind());
            }
        }
        this.monitor = monitor;
    }

    /** Validates the graph and returns gene ids in topological order; cycles and dangling edges throw VALIDATION. */
    public static List<String> topologicalOrder(Genome genome) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (NodeGene n : genome.nodes()) {
            if (indegree.put(n.geneId(), 0) != null) {
                throw MobiusException.invalid("duplicate node id " + n.geneId());
            }
        }
        if (indegree.isEmpty()) {
            throw MobiusException.invalid("genome " + genome.id() + " has no nodes");
        }
        Map<String, List<String>> out = new HashMap<>();
        for (EdgeGene e : genome.edges()) {
            if (!indegree.containsKey(e.from()) || !indegree.containsKey(e.to())) {
                throw MobiusException.invalid("edge " + e.from() + "->" + e.to() + " references an unknown node");
            }
            indegree.merge(e.to(), 1, Integer::sum);
            out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String to : out.getOrDefault(id, List.of())) {
                if (indegree.merge(to, -1, Integer::sum) == 0) {
                    ready.add(to);
                }
            }
        }
        if (order.size() != indegree.size()) {
            List<String> cyclic = indegree.keySet().stream().filter(id -> !order.contains(id)).toList();
            throw MobiusException.invalid("node graph has a cycle among " + cyclic);
        }
        return order;
    }

    /** Runs the context's genome on the invocation input. */
    public GraphResult execute(NodeContext ctx) {
        Genome genome = ctx.genome();
        List<String> order = topologicalOrder(genome);
        boolean sealed = ctx.invocation().sealed();
        Map<String, List<EdgeGene>> incoming = new HashMap<>();
        for (EdgeGene e : genome.edges()) {
            incoming.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        Set<String> ran = new HashSet<>();
        List<String> executed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, Object> result = null;
        Map<String, Object> outputValues = new LinkedHashMap<>();
        boolean sawOutput = false;
        double cost = 0;
        for (String geneId : order) {
            NodeGene gene = genome.node(geneId);
            List<EdgeGene> in = incoming.getOrDefault(geneId, List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            boolean active = in.isEmpty();
            if (in.isEmpty()) {
                input.putAll(ctx.invocation().input());
                input.remove(AgentInvocation.SEALED_KEY);
            }
            for (EdgeGene e : in) {
                if (ran.contains(e.from()) && conditionHolds(e.condition(), outputs.get(e.from()))) {
                    active = true;
                    Map<String, Object> payload = outputs.get(e.from());
                    input.putAll(payload);
                    monitor.flow(SourceKind.NODE, ctx.agentId() + "/" + e.from(), ctx.agentId() + "/" + geneId,
                            ctx.agentId(), e.from() + "->" + geneId, sealed ? null : payload);
                }
            }
            if (!active) {
                skipped.add(geneId);
                log.debug("node {}/{} skipped (no active incoming edge)", ctx.agentId(), geneId);
                continue;
            }
            NodeOutput out = runNode(ctx, gene, input, sealed);
            cost += out.costUsd();
            outputs.put(geneId, out.values());
            ran.add(geneId);
            executed.add(geneId);
            result = out.values();
            if (gene.kind() == NodeKind.OUTPUT) {
                sawOutput = true;
                outputValues.putAll(out.values());
            }
        }
        return new GraphResult(sawOutput ? outputValues : result == null ? Map.of() : result, cost, executed, skipped);
    }

    private NodeOutput runNode(NodeContext ctx, NodeGene gene, Map<String, Object> input, boolean sealed) {
        Node impl = nodes.get(gene.kind());
        String sourceId = ctx.agentId() + "/" + gene.geneId();
        try (Span span = monitor.start(SourceKind.NODE, sourceId, ctx.agentId(), gene.kind() + " " + gene.geneId())) {
            try {
                if (impl == null) {
                    throw new MobiusException(ErrorCode.VALIDATION, "no implementation for node kind " + gene.kind());
                }
                NodeOutput out = callWithTimeout(ctx, gene, impl, input);
                span.ok(sealed ? "sealed" : gene.kind() + " ok, keys=" + out.values().keySet());
                return out;
            } catch (RuntimeException e) {
                span.fail(e);
                return onError(ctx, gene, e);
            }
        }
    }

    private NodeOutput callWithTimeout(NodeContext ctx, NodeGene gene, Node impl, Map<String, Object> input) {
        long nodeTimeout = gene.config().get("timeoutMs") instanceof Number n ? n.longValue() : DEFAULT_TIMEOUT_MS;
        long remaining = ctx.deadline() == null ? Long.MAX_VALUE : Duration.between(Instant.now(), ctx.deadline()).toMillis();
        if (remaining <= 0) {
            throw new MobiusException(ErrorCode.TIMEOUT, "run deadline exceeded before node " + gene.geneId());
        }
        boolean runDeadlineBound = remaining < nodeTimeout;
        long wait = Math.min(nodeTimeout, remaining);
        Span parent = Span.current();
        FutureTask<NodeOutput> task = new FutureTask<>(() -> Span.callWithParent(parent, () -> impl.execute(ctx, gene, input)));
        Thread.ofVirtual().name("node-" + ctx.agentId() + "-" + gene.geneId()).start(task);
        try {
            return task.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            if (runDeadlineBound) {
                throw new MobiusException(ErrorCode.TIMEOUT, "run deadline exceeded in node " + gene.geneId());
            }
            throw new NodeFailedException(gene.geneId(), ErrorCode.TIMEOUT, "timed out after " + wait + " ms", null);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.TIMEOUT, "interrupted while running node " + gene.geneId());
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new MobiusException(ErrorCode.INTERNAL, "node " + gene.geneId() + ": " + c, c);
        }
    }

    private NodeOutput onError(NodeContext ctx, NodeGene gene, RuntimeException e) {
        String policy = String.valueOf(gene.config().getOrDefault("onError", "fail"));
        boolean runTimeout = e instanceof MobiusException se && se.code() == ErrorCode.TIMEOUT && !(e instanceof NodeFailedException);
        if (runTimeout || policy.equals("fail")) {
            log.warn("node {}/{} failed: {}", ctx.agentId(), gene.geneId(), e.getMessage());
            if (e instanceof NodeFailedException || runTimeout) {
                throw e;
            }
            ErrorCode code = e instanceof MobiusException se ? se.code() : ErrorCode.INTERNAL;
            throw new NodeFailedException(gene.geneId(), code, e.getMessage(), e);
        }
        log.warn("node {}/{} failed, policy {}: {}", ctx.agentId(), gene.geneId(), policy, e.getMessage());
        if (policy.equals("fallback")) {
            Object fb = gene.config().get("fallback");
            Map<String, Object> values = new LinkedHashMap<>();
            if (fb instanceof Map<?, ?> m) {
                m.forEach((k, v) -> values.put(String.valueOf(k), v));
            } else {
                values.put("value", fb);
            }
            return NodeOutput.of(values);
        }
        return NodeOutput.of(Map.of());
    }

    /** Evaluates {@code key == value} / {@code key != value} on a node output; null condition is always true. */
    static boolean conditionHolds(String condition, Map<String, Object> output) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        boolean negate = condition.contains("!=");
        String[] parts = condition.split(negate ? "!=" : "==", 2);
        if (parts.length != 2) {
            throw MobiusException.invalid("bad edge condition: " + condition);
        }
        String key = parts[0].trim();
        String expected = unquote(parts[1].trim());
        Object actual = output == null ? null : output.get(key);
        boolean eq = actual != null && (Objects.equals(String.valueOf(actual), expected) || numericEquals(actual, expected));
        return negate != eq;
    }

    private static boolean numericEquals(Object actual, String expected) {
        if (!(actual instanceof Number n)) {
            return false;
        }
        try {
            return n.doubleValue() == Double.parseDouble(expected);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
