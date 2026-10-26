package ai.mobiuslab.runtime;

import ai.mobiuslab.common.Ids;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeTemplates;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.runtime.node.CodeNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Requires the local MySQL (localhost:3307/mobiuslab) with migrations applied. */
@Tag("integration")
class AgentRunnerIntegrationTest {

    @Test
    void sameInvocationIdExecutesOnceAcrossRunnerInstances() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3307/mobiuslab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "mobiuslab", "mobiuslab");
        AgentRunRepository repo = new AgentRunRepository(JdbcClient.create(ds));
        AtomicInteger calls = new AtomicInteger();
        CodeFunction count = new BuiltinCodeFunctions.Fn("test.count", (in, cfg) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("n", calls.incrementAndGet());
        });
        String agentId = Ids.newId("agt");
        Genome g = GenomeTemplates.graph(agentId, "counter", null,
                List.of(GenomeTemplates.node("c", NodeKind.CODE, null, Map.of("function", "test.count"))), List.of());
        RuntimeFakes.FakeGenomes genomes = new RuntimeFakes.FakeGenomes();
        genomes.put(g);
        RuntimeProperties props = new RuntimeProperties(null, null, java.time.Duration.ofMillis(50));
        List<DefaultAgentRunner> runners = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            GraphExecutor ex = new GraphExecutor(List.of(new CodeNode(new CodeFunctionRegistry(List.of(count)))),
                    RuntimeFakes.monitor());
            runners.add(new DefaultAgentRunner(genomes, ex, repo, RuntimeFakes.monitor(), props));
        }
        String inv = Ids.newId("inv");
        List<AgentRunResult> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            DefaultAgentRunner r = runners.get(i % 2);
            threads.add(Thread.ofVirtual().start(() -> results.add(r.run(agentId, new AgentInvocation(inv, null, Map.of(), "t")))));
        }
        for (Thread t : threads) {
            t.join();
        }
        results.add(runners.get(0).run(agentId, new AgentInvocation(inv, null, Map.of(), "t")));
        assertEquals(1, calls.get());
        assertEquals(7, results.size());
        for (AgentRunResult r : results) {
            assertEquals(AgentRunResult.Status.OK, r.status(), String.valueOf(r.error()));
            assertEquals(1, ((Number) r.output().get("n")).intValue());
        }
        assertEquals("OK", repo.find(inv).orElseThrow().status());
    }
}
