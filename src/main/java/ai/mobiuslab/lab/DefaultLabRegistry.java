package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyGate;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/** Default {@link LabRegistry}: fair per-tool semaphores, idempotent request ids, timeouts and audit. */
@Service
public class DefaultLabRegistry implements LabRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultLabRegistry.class);

    private final LabStore store;
    private final MonitorBus monitor;
    private final ObjectProvider<SafetyGate> safety;
    private final BiPredicate<String, String> permission;
    private final Duration defaultTimeout;
    private final ConcurrentHashMap<String, Entry> tools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<LabResult>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private record Entry(LabTool tool, Semaphore permits) {
    }

    /** Spring constructor; {@code labPermission} (agentId, toolId) is optional and defaults to allow-all. */
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultLabRegistry(LabStore store, MonitorBus monitor, ObjectProvider<SafetyGate> safety,
                              @Qualifier("labPermission") ObjectProvider<BiPredicate<String, String>> labPermission,
                              LabProperties props) {
        this(store, monitor, safety, labPermission.getIfAvailable(() -> (a, t) -> true), props.defaultTimeout());
    }

    /** Direct constructor (tests). */
    public DefaultLabRegistry(LabStore store, MonitorBus monitor, ObjectProvider<SafetyGate> safety,
                              BiPredicate<String, String> permission, Duration defaultTimeout) {
        this.store = store;
        this.monitor = monitor;
        this.safety = safety;
        this.permission = permission == null ? (a, t) -> true : permission;
        this.defaultTimeout = defaultTimeout == null ? Duration.ofMinutes(5) : defaultTimeout;
    }

    @Override
    public void register(LabTool tool, Actor actor) {
        if (tool == null || tool.id() == null || tool.id().isBlank() || tool.policy() == null) {
            throw MobiusException.invalid("Lab tool needs an id and a policy");
        }
        ConcurrencyPolicy policy = tool.policy();
        if (policy.kind() == ConcurrencyPolicy.Kind.POOLED && policy.permits() < 1) {
            throw MobiusException.invalid("POOLED tool needs permits >= 1: " + tool.id());
        }
        if (actor.kind() != Actor.Kind.SYSTEM) {
            SafetyGate gate = safety.getIfAvailable();
            if (gate == null) {
                throw new MobiusException(ErrorCode.SAFETY_DENIED, "No safety gate available to review tool " + tool.id());
            }
            String content = tool instanceof AgentWrittenTool aw ? Json.write(aw.spec()) : tool.description();
            gate.require(ActionProposal.of(actor, ActionKind.LAB_TOOL_REGISTER, tool.id(), tool.description(), content));
        }
        boolean agentTool = tool instanceof AgentWrittenTool;
        String description = tool.description() == null ? "" : tool.description();
        if (description.length() > 2000) {
            description = description.substring(0, 2000);
        }
        store.saveTool(new LabStore.ToolRow(tool.id(), description, policy, agentTool ? "agent" : "builtin",
                agentTool ? ((AgentWrittenTool) tool).ownerAgentId() : null,
                agentTool ? ((AgentWrittenTool) tool).spec() : Map.of()), actor);
        tools.put(tool.id(), new Entry(tool, semaphoreFor(policy)));
        monitor.emit(ai.mobiuslab.monitor.MonitorEvent.of(SourceKind.LAB, tool.id(),
                        actor.kind() == Actor.Kind.AGENT ? actor.id() : null, EventType.STATE_CHANGE,
                        "tool registered (" + policy.kind() + ") by " + actor.asString()));
        log.info("Registered lab tool {} ({}, permits {}) by {}", tool.id(), policy.kind(), policy.permits(), actor.asString());
    }

    @Override
    public List<LabTool> list() {
        return tools.values().stream().map(Entry::tool).sorted(Comparator.comparing(LabTool::id)).toList();
    }

    @Override
    public LabResult invoke(String toolId, LabRequest request) {
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw MobiusException.invalid("Lab request needs a requestId");
        }
        Entry entry = tools.get(toolId);
        if (entry == null) {
            throw MobiusException.notFound("lab tool", toolId);
        }
        if (request.agentId() != null && !permission.test(request.agentId(), toolId)) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Agent " + request.agentId() + " may not use lab tool " + toolId);
        }
        Optional<LabResult> done = store.findFinished(request.requestId());
        if (done.isPresent()) {
            log.debug("Lab request {} already finished; returning stored result", request.requestId());
            return done.get();
        }
        CompletableFuture<LabResult> mine = new CompletableFuture<>();
        CompletableFuture<LabResult> existing = inFlight.putIfAbsent(request.requestId(), mine);
        if (existing != null) {
            return join(existing);
        }
        try {
            LabResult result = execute(toolId, entry, request);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(request.requestId(), mine);
        }
    }

    private LabResult execute(String toolId, Entry entry, LabRequest request) {
        Duration timeout = request.timeout() == null || request.timeout().isZero() || request.timeout().isNegative()
                ? defaultTimeout : request.timeout();
        Instant createdAt = Instant.now();
        try (Span span = monitor.start(SourceKind.LAB, toolId, request.agentId(),
                "lab call " + toolId + "." + request.operation())) {
            span.event(EventType.TOOL_CALL, toolId, request.operation(), safeJson(request.args()));
            long q0 = System.nanoTime();
            boolean acquired;
            try {
                acquired = entry.permits().tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MobiusException(ErrorCode.INTERNAL, "Interrupted waiting for lab tool " + toolId, e);
            }
            long queuedMs = (System.nanoTime() - q0) / 1_000_000;
            LabResult result;
            if (!acquired) {
                log.warn("Lab request {} timed out in queue for {} after {} ms", request.requestId(), toolId, queuedMs);
                result = new LabResult(false, Map.of(), null, 0, "queue timeout");
            } else {
                result = runWithPermit(toolId, entry, request, timeout);
            }
            try {
                store.saveCall(new LabStore.CallRow(request.requestId(), toolId, request.agentId(), request.operation(),
                        request.args(), result, queuedMs, createdAt));
            } catch (RuntimeException e) {
                log.error("Failed to persist lab call {} for {}: {}", request.requestId(), toolId, e.toString());
            }
            if (result.ok()) {
                span.ok("ok in " + result.durationMs() + " ms (queued " + queuedMs + " ms)");
            } else {
                span.fail(new MobiusException(ErrorCode.EXTERNAL_FAILURE, String.valueOf(result.error())));
            }
            return result;
        }
    }

    private LabResult runWithPermit(String toolId, Entry entry, LabRequest request, Duration timeout) {
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                entry.permits().release();
            }
        };
        long t0 = System.nanoTime();
        Future<LabResult> future;
        try {
            future = executor.submit(() -> {
                try {
                    return entry.tool().invoke(request);
                } finally {
                    release.run();
                }
            });
        } catch (RuntimeException e) {
            release.run();
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot schedule lab tool " + toolId, e);
        }
        try {
            LabResult r = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r == null) {
                return new LabResult(false, Map.of(), null, ms, "tool returned no result");
            }
            return r.durationMs() > 0 ? r : new LabResult(r.ok(), r.data(), r.log(), ms, r.error());
        } catch (TimeoutException e) {
            // The permit is released only when the tool really stops, so the policy is never exceeded.
            future.cancel(true);
            log.warn("Lab tool {} timed out after {} for request {}", toolId, timeout, request.requestId());
            return new LabResult(false, Map.of(), null, (System.nanoTime() - t0) / 1_000_000, "timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Lab tool {} failed for request {}: {}", toolId, request.requestId(), cause.toString());
            return new LabResult(false, Map.of(), null, (System.nanoTime() - t0) / 1_000_000,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "Interrupted while running lab tool " + toolId, e);
        }
    }

    private static LabResult join(CompletableFuture<LabResult> f) {
        try {
            return f.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static Semaphore semaphoreFor(ConcurrencyPolicy p) {
        return switch (p.kind()) {
            case EXCLUSIVE -> new Semaphore(1, true);
            case POOLED -> new Semaphore(p.permits(), true);
            case SHARED -> new Semaphore(Integer.MAX_VALUE, true);
        };
    }

    private static String safeJson(Map<String, Object> args) {
        try {
            return args == null ? null : Json.write(new LinkedHashMap<>(args));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Generates a fresh request id for callers without their own idempotency key. */
    public static String newRequestId() {
        return Ids.newId("lab");
    }

    /** Stops the worker executor. */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
