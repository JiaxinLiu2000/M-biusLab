package ai.mobiuslab.runtime;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.EdgeGene;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeTemplates;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.ChatResult;
import ai.mobiuslab.llm.JevAnswer;
import ai.mobiuslab.llm.JevRequest;
import ai.mobiuslab.llm.JevResult;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.prompt.PromptLibrary;
import ai.mobiuslab.prompt.PromptSegment;
import ai.mobiuslab.prompt.PromptTemplate;
import ai.mobiuslab.runtime.node.CodeNode;
import ai.mobiuslab.runtime.node.InputNode;
import ai.mobiuslab.runtime.node.JevDecisionNode;
import ai.mobiuslab.runtime.node.LlmNode;
import ai.mobiuslab.runtime.node.OutputNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.mobiuslab.genome.GenomeTemplates.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExecutorTest {

    private final List<JevRequest> jevCalls = new CopyOnWriteArrayList<>();
    private final List<ChatRequest> chatCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger counted = new AtomicInteger();

    private final LlmGateway llm = new LlmGateway() {
        @Override
        public ChatResult chat(ChatRequest request) {
            chatCalls.add(request);
            return new ChatResult("answer for " + request.messages().getLast().content(), null, "stop", null,
                    request.modelId(), 1, 0.01, "r1");
        }

        @Override
        public <T> T chatStructured(ChatRequest request, String jsonSchema, Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JevResult decide(JevRequest request) {
            jevCalls.add(request);
            return new JevResult("jev", Map.of("route", new JevAnswer("choice", null, "billing", null, null, 0.8),
                    "urgent", new JevAnswer("noul", 0.93, null, null, null, null)), null, 1, 0.002, "j1");
        }
    };

    private final PromptLibrary prompts = new PromptLibrary() {
        @Override
        public PromptTemplate latest(String key) {
            return new PromptTemplate(key, 3, null, List.of(), List.of(), null, 0, "SYSTEM:test", Instant.now());
        }

        @Override
        public PromptTemplate get(String key, int version) {
            return latest(key);
        }

        @Override
        public List<ChatMessage> render(String key, int version, Map<String, Object> variables) {
            return List.of(ChatMessage.system(key + "@" + version), ChatMessage.user(String.valueOf(variables.get("ticket"))));
        }

        @Override
        public PromptTemplate propose(String key, int baseVersion, List<PromptSegment> segments, ai.mobiuslab.common.Actor actor, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PromptTemplate> history(String key) {
            return List.of();
        }
    };

    private GraphExecutor executor() {
        List<CodeFunction> fns = new ArrayList<>(BuiltinCodeFunctions.all());
        fns.add(new BuiltinCodeFunctions.Fn("test.sleep", (in, cfg) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of();
        }));
        fns.add(new BuiltinCodeFunctions.Fn("test.count", (in, cfg) -> Map.of("n", counted.incrementAndGet())));
        List<Node> nodes = List.of(new InputNode(), new OutputNode(), new JevDecisionNode(llm), new LlmNode(prompts, llm),
                new CodeNode(new CodeFunctionRegistry(fns)));
        return new GraphExecutor(nodes, RuntimeFakes.monitor());
    }

    private static Genome routingGenome() {
        List<NodeGene> nodes = List.of(
                node("in", NodeKind.INPUT, null, Map.of()),
                node("jev", NodeKind.JEV_DECISION, null, Map.of("questions", Map.of(
                        "route", Map.of("type", "choice", "instructions", "Which team?",
                                "criteria", Map.of("billing", "money", "tech", "bugs")),
                        "urgent", Map.of("type", "noul", "instructions", "Urgent?")))),
                node("billing", NodeKind.LLM, "support.billing", Map.of()),
                node("tech", NodeKind.CODE, null, Map.of("function", "text.template", "template", "tech: {{ticket}}")),
                node("techFollow", NodeKind.CODE, null, Map.of("function", "test.count")),
                node("out", NodeKind.OUTPUT, null, Map.of()));
        List<EdgeGene> edges = List.of(new EdgeGene("in", "jev", null),
                new EdgeGene("jev", "billing", "route == billing"),
                new EdgeGene("jev", "tech", "route == 'tech'"),
                new EdgeGene("tech", "techFollow", null),
                new EdgeGene("jev", "out", null),
                new EdgeGene("billing", "out", null),
                new EdgeGene("techFollow", "out", null));
        return GenomeTemplates.graph("agt_router", "router", "m-chat", nodes, edges);
    }

    private static NodeContext ctx(Genome g, Map<String, Object> input) {
        return new NodeContext(g.agentId(), g, new AgentInvocation(null, null, input, "test"), null, 0,
                Instant.now().plusSeconds(30));
    }

    @Test
    void jevDecisionRoutesDownMatchingConditionalEdgeOnly() {
        GraphExecutor.GraphResult r = executor().execute(ctx(routingGenome(), Map.of("ticket", "refund my invoice")));
        assertEquals(List.of("in", "jev", "billing", "out"), r.executed());
        assertEquals(List.of("tech", "techFollow"), r.skipped());
        assertEquals("answer for refund my invoice", r.output().get("text"));
        assertEquals("billing", r.output().get("route"));
        assertEquals(0.8, r.output().get("route_confidence"));
        assertEquals(0.93, r.output().get("urgent"));
        assertEquals(0, counted.get());
        assertEquals(1, jevCalls.size());
        assertEquals(Map.of("ticket", "refund my invoice"), jevCalls.getFirst().state());
        assertEquals("m-chat", chatCalls.getFirst().modelId());
        assertEquals(3, chatCalls.getFirst().promptVersion());
        assertEquals(0.012, r.costUsd(), 1e-9);
    }

    @Test
    void cycleIsRejected() {
        Genome g = GenomeTemplates.graph("agt_c", "c", null,
                List.of(node("a", NodeKind.CODE, null, Map.of()), node("b", NodeKind.CODE, null, Map.of())),
                List.of(new EdgeGene("a", "b", null), new EdgeGene("b", "a", null)));
        MobiusException e = assertThrows(MobiusException.class, () -> executor().execute(ctx(g, Map.of())));
        assertEquals(ErrorCode.VALIDATION, e.code());
        assertTrue(e.getMessage().contains("cycle"));
    }

    @Test
    void nodeTimeoutProducesFailedRunWithNodeId() {
        Genome g = GenomeTemplates.graph("agt_slow", "slow", null,
                List.of(node("in", NodeKind.INPUT, null, Map.of()),
                        node("slowNode", NodeKind.CODE, null, Map.of("function", "test.sleep", "timeoutMs", 150))),
                List.of(new EdgeGene("in", "slowNode", null)));
        RuntimeFakes.FakeGenomes genomes = new RuntimeFakes.FakeGenomes();
        genomes.put(g);
        DefaultAgentRunner runner = new DefaultAgentRunner(genomes, executor(), new RuntimeFakes.FakeRuns(),
                RuntimeFakes.monitor(), RuntimeProperties.defaults());
        long t0 = System.currentTimeMillis();
        AgentRunResult r = runner.run("agt_slow", new AgentInvocation("inv-timeout", null, Map.of(), "test"));
        assertEquals(AgentRunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("slowNode"), r.error());
        assertTrue(System.currentTimeMillis() - t0 < 3_000);
    }

    @Test
    void fallbackPolicyReplacesFailedNodeOutput() {
        Genome g = GenomeTemplates.graph("agt_fb", "fb", null,
                List.of(node("in", NodeKind.INPUT, null, Map.of()),
                        node("bad", NodeKind.CODE, null, Map.of("function", "missing.fn", "onError", "fallback",
                                "fallback", Map.of("text", "default"))),
                        node("out", NodeKind.OUTPUT, null, Map.of())),
                List.of(new EdgeGene("in", "bad", null), new EdgeGene("bad", "out", null)));
        assertEquals("default", executor().execute(ctx(g, Map.of())).output().get("text"));
    }

    @Test
    void sameInvocationIdExecutesOnce() throws Exception {
        Genome g = GenomeTemplates.graph("agt_once", "once", null,
                List.of(node("c", NodeKind.CODE, null, Map.of("function", "test.count"))), List.of());
        RuntimeFakes.FakeGenomes genomes = new RuntimeFakes.FakeGenomes();
        genomes.put(g);
        DefaultAgentRunner runner = new DefaultAgentRunner(genomes, executor(), new RuntimeFakes.FakeRuns(),
                RuntimeFakes.monitor(), RuntimeProperties.defaults());
        List<Thread> threads = new ArrayList<>();
        List<AgentRunResult> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> results.add(runner.run("agt_once",
                    new AgentInvocation("inv-once", null, Map.of(), "test")))));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(1, counted.get());
        assertEquals(8, results.size());
        results.forEach(r -> assertEquals(1, r.output().get("n")));
    }
}
