package ai.mobiuslab.runtime;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.genome.EdgeGene;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeTemplates;
import ai.mobiuslab.genome.NodeGene;
import ai.mobiuslab.genome.NodeKind;
import ai.mobiuslab.llm.ChatMessage;
import ai.mobiuslab.llm.ChatRequest;
import ai.mobiuslab.llm.ChatResult;
import ai.mobiuslab.llm.JevRequest;
import ai.mobiuslab.llm.JevResult;
import ai.mobiuslab.llm.LlmGateway;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.MonitorStats;
import ai.mobiuslab.monitor.RecordingMonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.prompt.PromptLibrary;
import ai.mobiuslab.prompt.PromptRenderer;
import ai.mobiuslab.prompt.PromptSegment;
import ai.mobiuslab.prompt.PromptTemplate;
import ai.mobiuslab.runtime.node.InputNode;
import ai.mobiuslab.runtime.node.LlmNode;
import ai.mobiuslab.runtime.node.OutputNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static ai.mobiuslab.genome.GenomeTemplates.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for review findings #1 (default prompt vars), #12 (trace propagation) and sealed runs. */
class RuntimeReviewFixesTest {

    /** Records flow payloads and delegates spans to a recording bus. */
    static final class FlowBus implements MonitorBus {
        final RecordingMonitorBus spans = new RecordingMonitorBus();
        final List<Object[]> flows = new CopyOnWriteArrayList<>();

        @Override
        public void emit(MonitorEvent event) {
            spans.emit(event);
        }

        @Override
        public Span start(SourceKind kind, String sourceId, String agentId, String summary) {
            return spans.start(kind, sourceId, agentId, summary);
        }

        @Override
        public void flow(SourceKind kind, String fromId, String toId, String agentId, String edgeId, Object payload) {
            flows.add(new Object[]{edgeId, payload});
        }

        @Override
        public AutoCloseable subscribe(Consumer<MonitorEvent> listener) {
            return () -> {
            };
        }

        @Override
        public List<MonitorEvent> recent(int limit) {
            return spans.recent(limit);
        }

        @Override
        public MonitorStats stats() {
            return new MonitorStats();
        }
    }

    private static Map<String, List<PromptSegment>> seedSegments() throws Exception {
        Map<String, List<PromptSegment>> out = new HashMap<>();
        try (InputStream in = RuntimeReviewFixesTest.class.getResourceAsStream("/prompts/platform.yml")) {
            JsonNode root = new YAMLMapper().readTree(in);
            for (JsonNode p : root.get("prompts")) {
                List<PromptSegment> segs = new ArrayList<>();
                for (JsonNode s : p.get("segments")) {
                    segs.add(new PromptSegment(ChatMessage.Role.valueOf(s.get("role").asText()), s.get("text").asText(),
                            s.get("volatility").asInt()));
                }
                out.put(p.get("key").asText(), segs);
            }
        }
        return out;
    }

    private static PromptLibrary seedLibrary(Map<String, List<PromptSegment>> seeds) {
        return new PromptLibrary() {
            @Override
            public PromptTemplate latest(String key) {
                return new PromptTemplate(key, 1, null, seeds.get(key), List.of(), null, 0, "test", Instant.now());
            }

            @Override
            public PromptTemplate get(String key, int version) {
                return latest(key);
            }

            @Override
            public List<ChatMessage> render(String key, int version, Map<String, Object> variables) {
                return PromptRenderer.render(key, seeds.get(key), variables);
            }

            @Override
            public PromptTemplate propose(String key, int baseVersion, List<PromptSegment> segments, Actor actor, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PromptTemplate> history(String key) {
                return List.of();
            }
        };
    }

    private final List<ChatRequest> chats = new CopyOnWriteArrayList<>();

    private final LlmGateway llm = new LlmGateway() {
        @Override
        public ChatResult chat(ChatRequest request) {
            chats.add(request);
            return new ChatResult("done", null, "stop", null, request.modelId(), 1, 0.0, "r1");
        }

        @Override
        public <T> T chatStructured(ChatRequest request, String jsonSchema, Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JevResult decide(JevRequest request) {
            throw new UnsupportedOperationException();
        }
    };

    private static NodeContext ctx(Genome g, Map<String, Object> input) {
        return new NodeContext(g.agentId(), g, new AgentInvocation(null, null, input, "test"), null, 0,
                Instant.now().plusSeconds(30));
    }

    @Test
    void genericTaskPromptRendersWithDefaultVars() throws Exception {
        Genome g = GenomeTemplates.singleLlmAgent("agt_generic", "researcher", "finds facts", "agent.generic.task", "m-chat");
        GraphExecutor ex = new GraphExecutor(List.of(new InputNode(), new OutputNode(),
                new LlmNode(seedLibrary(seedSegments()), llm)), RuntimeFakes.monitor());
        Map<String, Object> input = Map.of("task", "summarize X");
        GraphExecutor.GraphResult r = ex.execute(ctx(g, input));
        assertEquals("done", r.output().get("text"));
        String all = chats.getFirst().messages().stream().map(ChatMessage::content).reduce("", String::concat);
        assertTrue(all.contains("Role: researcher"), all);
        assertTrue(all.contains("Description: finds facts"), all);
        assertTrue(all.contains(Json.write(input)), all);
    }

    @Test
    void nestedSpanInsideNodeSharesAgentTrace() {
        List<String> nestedTrace = new CopyOnWriteArrayList<>();
        FlowBus bus = new FlowBus();
        Node probe = new Node() {
            @Override
            public NodeKind kind() {
                return NodeKind.CODE;
            }

            @Override
            public NodeOutput execute(NodeContext c, NodeGene gene, Map<String, Object> input) {
                try (Span s = bus.start(SourceKind.LLM, "probe", c.agentId(), "nested")) {
                    nestedTrace.add(s.traceId());
                }
                return NodeOutput.of(Map.of("ok", true));
            }
        };
        Genome g = GenomeTemplates.graph("agt_trace", "t", "m-chat",
                List.of(node("in", NodeKind.INPUT, null, Map.of()), node("probe", NodeKind.CODE, null, Map.of())),
                List.of(new EdgeGene("in", "probe", null)));
        GraphExecutor ex = new GraphExecutor(List.of(new InputNode(), probe), bus);
        String agentTrace;
        try (Span agent = bus.start(SourceKind.AGENT, "agt_trace", "agt_trace", "run")) {
            agentTrace = agent.traceId();
            ex.execute(ctx(g, Map.of()));
        }
        assertEquals(List.of(agentTrace), nestedTrace);
        assertNull(Span.current());
    }

    @Test
    void sealedRunKeepsPayloadsOutOfMonitor() {
        FlowBus bus = new FlowBus();
        Genome g = GenomeTemplates.graph("agt_sealed", "s", "m-chat",
                List.of(node("in", NodeKind.INPUT, null, Map.of()), node("out", NodeKind.OUTPUT, null, Map.of())),
                List.of(new EdgeGene("in", "out", null)));
        GraphExecutor ex = new GraphExecutor(List.of(new InputNode(), new OutputNode()), bus);
        GraphExecutor.GraphResult r = ex.execute(ctx(g,
                Map.of("question", "SECRET-HELD-OUT-42", AgentInvocation.SEALED_KEY, true)));
        assertFalse(r.output().containsKey(AgentInvocation.SEALED_KEY));
        assertFalse(bus.flows.isEmpty());
        for (Object[] f : bus.flows) {
            assertNull(f[1]);
        }
        for (MonitorEvent e : bus.spans.events) {
            assertFalse(String.valueOf(e).contains("SECRET-HELD-OUT-42"), String.valueOf(e));
        }

        ex.execute(ctx(g, Map.of("question", "visible")));
        assertNotNull(bus.flows.getLast()[1]);
    }

    @Test
    void sealedRunIsNotPersistedVerbatim() {
        Genome g = GenomeTemplates.graph("agt_sealed2", "s", "m-chat",
                List.of(node("in", NodeKind.INPUT, null, Map.of()), node("out", NodeKind.OUTPUT, null, Map.of())),
                List.of(new EdgeGene("in", "out", null)));
        RuntimeFakes.FakeGenomes genomes = new RuntimeFakes.FakeGenomes();
        Genome saved = genomes.save(g, Actor.SYSTEM, "test");
        Map<String, Object> startedWith = new HashMap<>();
        Map<String, AgentRunResult> finished = new HashMap<>();
        AgentRunRepository runs = new AgentRunRepository(null) {
            @Override
            public boolean start(String invocationId, String agentId, String genomeId, String traceId, Map<String, Object> input) {
                startedWith.putAll(input);
                return true;
            }

            @Override
            public void finish(AgentRunResult r, String traceId) {
                finished.put(r.invocationId(), r);
            }

            @Override
            public java.util.Optional<StoredRun> find(String invocationId) {
                return java.util.Optional.empty();
            }
        };
        MonitorBus bus = RuntimeFakes.monitor();
        DefaultAgentRunner runner = new DefaultAgentRunner(genomes,
                new GraphExecutor(List.of(new InputNode(), new OutputNode()), bus), runs, bus,
                RuntimeProperties.defaults());
        AgentInvocation inv = new AgentInvocation(null, null, Map.of("q", "SECRET", AgentInvocation.SEALED_KEY, true), "test");
        AgentRunResult r = runner.runGenome(saved.id(), inv);
        assertEquals(AgentRunResult.Status.OK, r.status(), r.error());
        assertEquals("SECRET", r.output().get("q"));
        assertEquals(Map.of("sealed", true), startedWith);
        assertEquals(Map.of("sealed", true), finished.get(inv.invocationId()).output());
    }
}
