package ai.mobiuslab.llm;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.monitor.AsyncMonitorBus;
import ai.mobiuslab.monitor.MonitorRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gateway behaviour against a fake Crusoe / Jev HTTP server. */
class DefaultLlmGatewayTest {

    /** Fake response. */
    record Resp(int status, String body, Map<String, String> headers) {
        static Resp ok(String body) {
            return new Resp(200, body, Map.of());
        }
    }

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile Function<Integer, Resp> handler;
    private DefaultLlmGateway gateway;
    private UsageMeter meter;
    private AsyncMonitorBus bus;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        MobiusProperties mobius = new MobiusProperties("test", "./target/test-home",
                new MobiusProperties.Endpoint(base + "/v1", "key", Duration.ofSeconds(5), 4),
                new MobiusProperties.Endpoint(base, "key", Duration.ofSeconds(5), 4),
                null, null, new MobiusProperties.Monitor(1000, 100, Duration.ofMillis(100), 100),
                new MobiusProperties.Budget(50, 0.8));
        LlmProperties props = new LlmProperties(null, null, null, null, Duration.ofMillis(5), Duration.ofMillis(20),
                null, null, null, Duration.ofMillis(50));
        bus = new AsyncMonitorBus(Mockito.mock(MonitorRepository.class), mobius);
        meter = new UsageMeter(Mockito.mock(LlmCallRepository.class), mobius, props);
        gateway = new DefaultLlmGateway(new CrusoeClient(mobius.crusoe(), props), new JevClient(mobius.jev()),
                new ModelRegistry(), meter, bus, mobius, props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        meter.shutdown();
        bus.shutdown();
    }

    private void handle(HttpExchange ex) throws IOException {
        bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Resp r = handler.apply(calls.incrementAndGet());
        r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String completion(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return """
                {"id":"cmpl-1","choices":[{"message":{"role":"assistant","content":"%s","reasoning_content":"think"},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":1000,"completion_tokens":100,
                "prompt_tokens_details":{"cached_tokens":400}}}""".formatted(escaped);
    }

    private static ChatRequest req() {
        return ChatRequest.of("test.module", "agt_1", List.of(ChatMessage.system("sys"), ChatMessage.user("hi")));
    }

    /** Typed structured result. */
    record Plan(String title, int steps) {
    }

    private static final String SCHEMA = """
            {"type":"object","properties":{"title":{"type":"string"},"steps":{"type":"integer"}},
             "required":["title","steps"],"additionalProperties":false}""";

    @Test
    void retries429ThenSucceedsAndRecordsUsage() {
        handler = n -> n == 1 ? new Resp(429, "{\"error\":\"slow down\"}", Map.of()) : Resp.ok(completion("hello"));
        ChatResult r = gateway.chat(req());
        assertEquals("hello", r.content());
        assertEquals("think", r.reasoning());
        assertEquals("stop", r.finishReason());
        assertEquals(2, calls.get());
        assertEquals(new TokenUsage(1000, 400, 100), r.usage());
        UsageReport.Snapshot s = meter.snapshot();
        assertEquals(1, s.calls());
        assertEquals(1000, s.promptTokens());
        assertEquals(400, s.cachedTokens());
        double expected = (600 * 0.05 + 400 * 0.03 + 100 * 0.20) / 1_000_000;
        assertEquals(expected, r.costUsd(), 1e-12);
        assertEquals(expected, s.costUsd(), 1e-12);
        UsageReport.Row module = s.byModule().get(0);
        assertEquals("test.module", module.key());
        assertEquals(1, module.callsLastMinute());
        assertEquals(0.4, module.cacheHitRatio(), 1e-9);
    }

    @Test
    void structuredRepairsOnceThenReturnsTypedObject() {
        handler = n -> n == 1 ? Resp.ok(completion("{\"title\":\"x\"}"))
                : Resp.ok(completion("```json\n{\"title\":\"plan\",\"steps\":3}\n```"));
        Plan p = gateway.chatStructured(req(), SCHEMA, Plan.class);
        assertEquals(new Plan("plan", 3), p);
        assertEquals(2, calls.get());
        assertTrue(bodies.get(0).contains("\"response_format\""));
        assertTrue(bodies.get(1).contains("not valid"), "repair turn lists validation errors");
    }

    @Test
    void alwaysInvalidFailsAfterFourAttempts() {
        handler = n -> Resp.ok(completion("not json at all"));
        MobiusException e = assertThrows(MobiusException.class, () -> gateway.chatStructured(req(), SCHEMA, Plan.class));
        assertEquals(ErrorCode.LLM_BAD_OUTPUT, e.code());
        assertEquals(4, calls.get());
    }

    @Test
    void responseFormatRejectionFallsBackToSchemaInPrompt() {
        handler = n -> bodies.get(n - 1).contains("response_format")
                ? new Resp(400, "{\"error\":\"response_format is not supported for this model\"}", Map.of())
                : Resp.ok(completion("{\"title\":\"t\",\"steps\":1}"));
        Plan p = gateway.chatStructured(req().withModel("nvidia/Nemotron-3-Super-120B-A12B"), SCHEMA, Plan.class);
        assertEquals(new Plan("t", 1), p);
        assertEquals(2, calls.get());
        assertFalse(bodies.get(1).contains("response_format"));
        assertTrue(bodies.get(1).contains("JSON Schema"));
        // the model is remembered: no second rejected round trip
        gateway.chatStructured(req().withModel("nvidia/Nemotron-3-Super-120B-A12B"), SCHEMA, Plan.class);
        assertEquals(3, calls.get());
    }

    @Test
    void badRequestIsNotRetried() {
        handler = n -> new Resp(401, "{\"error\":\"bad key\"}", Map.of());
        MobiusException e = assertThrows(MobiusException.class, () -> gateway.chat(req()));
        assertFalse(e.retryable());
        assertEquals(1, calls.get());
    }

    @Test
    void temperatureZeroResultIsCached() {
        handler = n -> Resp.ok(completion("same"));
        ChatRequest r = new ChatRequest("m", null, null, List.of(ChatMessage.user("q")), null, 0.0, null, null, null, null);
        gateway.chat(r);
        gateway.chat(r);
        assertEquals(1, calls.get());
    }

    @Test
    void jevAnswersAreParsedPerType() {
        handler = n -> new Resp(200, """
                {"model":"jev-1.13.0","answers":{
                  "q1":{"type":"noul","noul":0.95},
                  "q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.88,"technical":0.12},"confidence":0.81},
                  "q3":{"type":"score","score":1.4,"legend":["Calm","Frustrated","Very angry"],"probabilities":{"0":0.1,"1":0.4,"2":0.5},"confidence":0.6}},
                 "usage":{"input_tokens":296,"output_tokens":20}}""", Map.of("x-typesafe-request-id", "req-42"));
        LinkedHashMap<String, JevQuestion> qs = new LinkedHashMap<>();
        qs.put("q1", new JevQuestion.Noul("Urgent?", null));
        LinkedHashMap<String, Object> opts = new LinkedHashMap<>();
        opts.put("billing", "Payments");
        opts.put("technical", null);
        qs.put("q2", new JevQuestion.Choice("Team?", opts));
        qs.put("q3", new JevQuestion.Score("Frustration?", List.of("Calm", "Frustrated", "Very angry")));
        JevResult r = gateway.decide(new JevRequest("test", null, null, "payouts failing", qs));
        assertTrue(r.answer("q1").yes(0.9));
        assertEquals("billing", r.answer("q2").choice());
        assertEquals(0.81, r.answer("q2").confidence());
        assertEquals(0.88, r.answer("q2").probabilities().get("billing"));
        assertEquals(1.4, r.answer("q3").score());
        assertEquals(0.5, r.answer("q3").probabilities().get("2"));
        assertEquals("req-42", r.requestId());
        assertEquals(new TokenUsage(296, 0, 20), r.usage());
        assertEquals(296 * 0.042 / 1_000_000, r.costUsd(), 1e-12);
        assertTrue(bodies.get(0).contains("\"type\":\"score\""));
        assertTrue(bodies.get(0).contains("\"model\":\"jev-latest\""));
    }

    @Test
    void missingKeyFailsFast() {
        MobiusProperties mobius = new MobiusProperties("t", ".", new MobiusProperties.Endpoint(null, "", null, 1), null,
                null, null, null, null);
        DefaultLlmGateway g = new DefaultLlmGateway(new CrusoeClient(mobius.crusoe(), LlmProperties.defaults()),
                new JevClient((MobiusProperties.Endpoint) null), new ModelRegistry(), meter, bus, mobius, LlmProperties.defaults());
        MobiusException e = assertThrows(MobiusException.class, () -> g.chat(req()));
        assertEquals(ErrorCode.LLM_UNAVAILABLE, e.code());
        assertTrue(e.getMessage().contains("CRUSOE_API_KEY not configured"));
        assertNotNull(meter.snapshot());
    }
}
