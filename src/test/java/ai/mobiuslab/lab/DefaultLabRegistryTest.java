package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.safety.SafetyGate;
import ai.mobiuslab.workspace.WorkspaceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultLabRegistryTest {

    /** In-memory store. */
    static final class MemStore implements LabStore {
        final Map<String, LabResult> calls = new ConcurrentHashMap<>();
        final Map<String, ToolRow> tools = new ConcurrentHashMap<>();

        @Override
        public Optional<LabResult> findFinished(String requestId) {
            return Optional.ofNullable(calls.get(requestId));
        }

        @Override
        public void saveCall(CallRow call) {
            calls.putIfAbsent(call.requestId(), call.result());
        }

        @Override
        public void saveTool(ToolRow tool, Actor actor) {
            tools.put(tool.id(), tool);
        }

        @Override
        public List<ToolRow> agentTools() {
            return List.of();
        }
    }

    /** Tool that tracks concurrency. */
    static final class CountingTool implements LabTool {
        final String id;
        final ConcurrencyPolicy policy;
        final long sleepMs;
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();

        CountingTool(String id, ConcurrencyPolicy policy, long sleepMs) {
            this.id = id;
            this.policy = policy;
            this.sleepMs = sleepMs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public ConcurrencyPolicy policy() {
            return policy;
        }

        @Override
        public LabResult invoke(LabRequest request) {
            runs.incrementAndGet();
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return new LabResult(true, Map.of("n", runs.get()), null, 0, null);
        }
    }

    private final MemStore store = new MemStore();

    private DefaultLabRegistry registry(SafetyGate gate) {
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        if (gate != null) {
            bf.addBean("gate", gate);
        }
        ObjectProvider<SafetyGate> provider = bf.getBeanProvider(SafetyGate.class);
        return new DefaultLabRegistry(store, WorkspaceTestSupport.monitor(), provider, null, Duration.ofSeconds(30));
    }

    private static int hammer(DefaultLabRegistry r, String toolId, int threads, boolean sameId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<LabResult>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String rid = sameId ? "same" : "r" + i;
                fs.add(pool.submit(() -> {
                    go.await();
                    return r.invoke(toolId, new LabRequest(rid, "agent1", "run", Map.of(), null));
                }));
            }
            go.countDown();
            int ok = 0;
            for (Future<LabResult> f : fs) {
                if (f.get(60, TimeUnit.SECONDS).ok()) {
                    ok++;
                }
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void exclusiveNeverConcurrent() throws Exception {
        DefaultLabRegistry r = registry(null);
        CountingTool t = new CountingTool("ex", ConcurrencyPolicy.exclusive(), 30);
        r.register(t, Actor.SYSTEM);
        assertEquals(8, hammer(r, "ex", 8, false));
        assertEquals(1, t.max.get());
        assertEquals(8, t.runs.get());
        assertEquals(8, store.calls.size());
    }

    @Test
    void pooledNeverExceedsPermits() throws Exception {
        DefaultLabRegistry r = registry(null);
        CountingTool t = new CountingTool("pool", ConcurrencyPolicy.pooled(2), 50);
        r.register(t, Actor.SYSTEM);
        assertEquals(8, hammer(r, "pool", 8, false));
        assertEquals(2, t.max.get());
    }

    @Test
    void sameRequestIdExecutesOnce() throws Exception {
        DefaultLabRegistry r = registry(null);
        CountingTool t = new CountingTool("idem", new ConcurrencyPolicy(ConcurrencyPolicy.Kind.SHARED, 0), 200);
        r.register(t, Actor.SYSTEM);
        assertEquals(5, hammer(r, "idem", 5, true));
        assertEquals(1, t.runs.get());
        assertTrue(r.invoke("idem", new LabRequest("same", "agent1", "run", Map.of(), null)).ok());
        assertEquals(1, t.runs.get());
    }

    @Test
    void timeoutReturnsNotOk() {
        DefaultLabRegistry r = registry(null);
        r.register(new CountingTool("slow", ConcurrencyPolicy.exclusive(), 5000), Actor.SYSTEM);
        LabResult res = r.invoke("slow", new LabRequest("t1", "agent1", "run", Map.of(), Duration.ofMillis(200)));
        assertFalse(res.ok());
        assertEquals("timeout", res.error());
    }

    @Test
    void agentRegistrationRequiresSafety() {
        DefaultLabRegistry noGate = registry(null);
        MobiusException e = assertThrows(MobiusException.class,
                () -> noGate.register(new CountingTool("x", ConcurrencyPolicy.exclusive(), 1), Actor.agent("a")));
        assertEquals(ErrorCode.SAFETY_DENIED, e.code());

        AtomicInteger reviews = new AtomicInteger();
        SafetyGate gate = (SafetyGate) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SafetyGate.class}, (p, m, a) -> {
                    reviews.incrementAndGet();
                    return null;
                });
        DefaultLabRegistry withGate = registry(gate);
        withGate.register(new CountingTool("y", ConcurrencyPolicy.exclusive(), 1), Actor.agent("a"));
        assertEquals(1, reviews.get());
        assertEquals(1, withGate.list().size());
        assertTrue(store.tools.containsKey("y"));
    }
}
