package ai.mobiuslab.llm;

import ai.mobiuslab.common.Backoff;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Default {@link LlmGateway}: Crusoe for chat, Jev for decisions; backoff retries of transient errors,
 * per-provider circuit breakers, idempotent result cache, structured-output repair loop, usage accounting
 * and monitor reporting for every call.
 */
@Service
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);

    private final CrusoeClient crusoe;
    private final JevClient jev;
    private final ModelRegistry models;
    private final UsageMeter usage;
    private final MonitorBus monitor;
    private final LlmProperties props;
    private final Backoff crusoeBackoff;
    private final Backoff jevBackoff;
    private final CircuitBreaker crusoeBreaker;
    private final CircuitBreaker jevBreaker;
    private final Cache<String, ChatResult> resultCache;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();
    private final Set<String> noResponseFormat = ConcurrentHashMap.newKeySet();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** Wires the gateway. */
    public DefaultLlmGateway(CrusoeClient crusoe, JevClient jev, ModelRegistry models, UsageMeter usage,
                             MonitorBus monitor, MobiusProperties mobius, LlmProperties props) {
        this.crusoe = crusoe;
        this.jev = jev;
        this.models = models;
        this.usage = usage;
        this.monitor = monitor;
        this.props = props;
        this.crusoeBackoff = new Backoff(attempts(mobius.crusoe()), props.backoffBase(), props.backoffCap());
        this.jevBackoff = new Backoff(attempts(mobius.jev()), props.backoffBase(), props.backoffCap());
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordException(e -> !(e instanceof MobiusException s) || s.retryable())
                .build();
        this.crusoeBreaker = CircuitBreaker.of("crusoe", cfg);
        this.jevBreaker = CircuitBreaker.of("jev", cfg);
        this.resultCache = Caffeine.newBuilder()
                .expireAfterWrite(props.resultCacheTtl())
                .maximumSize(props.resultCacheSize())
                .build();
    }

    private static int attempts(MobiusProperties.Endpoint e) {
        return e == null || e.maxRetries() < 1 ? 4 : e.maxRetries();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        return execute(request, null);
    }

    @Override
    public <T> T chatStructured(ChatRequest request, String jsonSchema, Class<T> type) {
        JsonSchema schema = schemas.computeIfAbsent(jsonSchema, s -> {
            try {
                return schemaFactory.getSchema(s);
            } catch (RuntimeException e) {
                throw MobiusException.invalid("invalid JSON schema for " + type.getSimpleName() + ": " + e.getMessage());
            }
        });
        Object schemaTree = Json.read(jsonSchema, Object.class);
        String model = resolveModel(request);
        ChatRequest base = request.withModel(model);
        String name = type.getSimpleName().replaceAll("[^A-Za-z0-9_-]", "_");
        List<ChatMessage> repairs = new ArrayList<>();
        int maxAttempts = 1 + props.structuredRetries();
        String lastErrors = "";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ChatResult result = structuredAttempt(base, repairs, model, name, schemaTree, jsonSchema);
            String content = result.content();
            List<String> errors = new ArrayList<>();
            T value = null;
            try {
                JsonNode node = Json.mapper().readTree(extractJson(content));
                if (node == null || node.isMissingNode()) {
                    errors.add("reply contains no JSON value");
                } else {
                    for (ValidationMessage m : schema.validate(node)) {
                        errors.add(m.getMessage());
                    }
                    if (errors.isEmpty()) {
                        value = Json.mapper().treeToValue(node, type);
                    }
                }
            } catch (JsonProcessingException | IllegalArgumentException e) {
                errors.add("not valid JSON for " + type.getSimpleName() + ": " + (e instanceof JsonProcessingException j
                        ? j.getOriginalMessage() : e.getMessage()));
            }
            if (errors.isEmpty()) {
                return value;
            }
            lastErrors = String.join("; ", errors);
            log.warn("structured output from {} for {} invalid (attempt {}/{}): {}", model, request.callerModule(),
                    attempt, maxAttempts, lastErrors);
            reportDecision(request, model, "structured output invalid (attempt " + attempt + ")", lastErrors);
            repairs.add(ChatMessage.assistant(content));
            repairs.add(ChatMessage.user("Your previous reply was not valid. Fix these problems and reply with only "
                    + "the corrected JSON:\n- " + String.join("\n- ", errors)));
        }
        throw new MobiusException(ErrorCode.LLM_BAD_OUTPUT, "structured output for " + type.getSimpleName() + " from "
                + model + " still invalid after " + maxAttempts + " attempts: " + lastErrors);
    }

    private ChatResult structuredAttempt(ChatRequest base, List<ChatMessage> repairs, String model, String name,
                                         Object schemaTree, String jsonSchema) {
        if (!noResponseFormat.contains(model)) {
            Map<String, Object> rf = Map.of("type", "json_schema",
                    "json_schema", Map.of("name", name, "schema", schemaTree, "strict", true));
            try {
                return execute(base.appending(repairs), rf);
            } catch (LlmHttpException e) {
                String body = e.body().toLowerCase(Locale.ROOT);
                if (e.status() != 400 || !(body.contains("response_format") || body.contains("json_schema"))) {
                    throw e;
                }
                noResponseFormat.add(model);
                log.warn("model {} rejected response_format; falling back to schema-in-prompt", model);
            }
        }
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("Reply with only a JSON value (no prose, no code fences) that is valid against "
                + "this JSON Schema:\n" + jsonSchema));
        msgs.addAll(repairs);
        return execute(base.appending(msgs), null);
    }

    /** Extracts the JSON payload: strips ``` fences and surrounding prose. */
    static String extractJson(String content) {
        String s = content == null ? "" : content.strip();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", fence + 3);
            if (start >= 0 && end > start) {
                s = s.substring(start + 1, end).strip();
            }
        }
        if (!s.isEmpty() && s.charAt(0) != '{' && s.charAt(0) != '[') {
            int obj = s.indexOf('{');
            int arr = s.indexOf('[');
            int i = obj < 0 ? arr : (arr < 0 ? obj : Math.min(obj, arr));
            if (i >= 0) {
                char close = s.charAt(i) == '{' ? '}' : ']';
                int j = s.lastIndexOf(close);
                if (j > i) {
                    s = s.substring(i, j + 1);
                }
            }
        }
        return s;
    }

    private String resolveModel(ChatRequest request) {
        return request.modelId() == null || request.modelId().isBlank()
                ? models.defaultFor(ModelRegistry.DEFAULT_ROLE) : request.modelId();
    }

    private ChatResult execute(ChatRequest request, Map<String, Object> responseFormat) {
        if (!crusoe.configured()) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "CRUSOE_API_KEY not configured");
        }
        String model = resolveModel(request);
        String cacheKey = request.temperature() != null && request.temperature() == 0.0
                ? Json.sha256(Map.of("request", request.withModel(model), "rf", responseFormat == null ? Map.of() : responseFormat))
                : null;
        if (cacheKey != null) {
            ChatResult hit = resultCache.getIfPresent(cacheKey);
            if (hit != null) {
                monitor.emit(ai.mobiuslab.monitor.MonitorEvent.of(SourceKind.LLM, request.callerModule(), request.agentId(),
                        EventType.LLM_CALL, "result cache hit " + model).withTarget(model)
                        .withPayload(Json.write(Map.of("model", model, "resultCache", true))));
                return hit;
            }
        }
        int maxTokens = request.maxTokens() == null ? props.defaultMaxTokens() : request.maxTokens();
        String callId = Ids.newId("llm");
        long start = System.nanoTime();
        try (Span span = monitor.start(SourceKind.LLM, request.callerModule(), request.agentId(), "chat " + model)) {
            try {
                CrusoeClient.Reply reply = crusoeBackoff.call("crusoe " + model, () -> guarded(crusoeBreaker,
                        () -> completeWithLengthRetry(model, request, maxTokens, responseFormat)));
                long latency = (System.nanoTime() - start) / 1_000_000;
                double cost = models.cost(model, reply.usage());
                ChatResult result = new ChatResult(reply.content(), reply.reasoning(), reply.finishReason(),
                        reply.usage(), model, latency, cost, reply.requestId());
                usage.record(callRecord(callId, ModelRegistry.Provider.CRUSOE, model, request.callerModule(),
                        request.agentId(), request.promptKey(), request.promptVersion(), reply.usage(), latency, cost,
                        "OK", null, reply.requestId()));
                span.event(EventType.LLM_CALL, model, "chat " + model + " " + reply.usage().promptTokens() + "+"
                        + reply.usage().completionTokens() + " tok", Json.write(payload(callId, model, request.promptKey(),
                        request.promptVersion(), reply.usage(), cost, latency, reply.finishReason(), reply.requestId())));
                span.ok(reply.finishReason());
                if (cacheKey != null) {
                    resultCache.put(cacheKey, result);
                }
                return result;
            } catch (RuntimeException e) {
                long latency = (System.nanoTime() - start) / 1_000_000;
                usage.record(callRecord(callId, ModelRegistry.Provider.CRUSOE, model, request.callerModule(),
                        request.agentId(), request.promptKey(), request.promptVersion(), TokenUsage.ZERO, latency, 0,
                        "FAIL", e.getMessage(), null));
                span.fail(e);
                throw e;
            }
        }
    }

    private CrusoeClient.Reply completeWithLengthRetry(String model, ChatRequest r, int maxTokens,
                                                       Map<String, Object> rf) {
        CrusoeClient.Reply reply = crusoe.complete(model, r.messages(), maxTokens, r.temperature(), r.extraBody(), rf, r.timeout());
        if (reply.content().isBlank() && "length".equals(reply.finishReason())) {
            log.warn("{} returned empty content with finish_reason=length at max_tokens={}; retrying with {}",
                    model, maxTokens, maxTokens * 2);
            CrusoeClient.Reply second = crusoe.complete(model, r.messages(), maxTokens * 2, r.temperature(), r.extraBody(), rf, r.timeout());
            TokenUsage u = new TokenUsage(reply.usage().promptTokens() + second.usage().promptTokens(),
                    reply.usage().cachedTokens() + second.usage().cachedTokens(),
                    reply.usage().completionTokens() + second.usage().completionTokens());
            return new CrusoeClient.Reply(second.content(), second.reasoning(), second.finishReason(), u, second.requestId());
        }
        return reply;
    }

    @Override
    public JevResult decide(JevRequest request) {
        if (!jev.configured()) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "TYPESAFE_API_KEY not configured");
        }
        String model = request.modelId() == null || request.modelId().isBlank() ? JevClient.DEFAULT_MODEL : request.modelId();
        String callId = Ids.newId("jev");
        long start = System.nanoTime();
        try (Span span = monitor.start(SourceKind.LLM, request.callerModule(), request.agentId(),
                "decide " + request.questions().size() + " questions")) {
            try {
                JevClient.Reply reply = jevBackoff.call("jev " + model, () -> guarded(jevBreaker,
                        () -> jev.decide(model, request.state(), request.questions())));
                long latency = (System.nanoTime() - start) / 1_000_000;
                double cost = models.cost(model, reply.usage());
                JevResult result = new JevResult(reply.model() == null ? model : reply.model(), reply.answers(),
                        reply.usage(), latency, cost, reply.requestId());
                usage.record(callRecord(callId, ModelRegistry.Provider.JEV, model, request.callerModule(),
                        request.agentId(), null, null, reply.usage(), latency, cost, "OK", null, reply.requestId()));
                Map<String, Object> p = payload(callId, model, null, null, reply.usage(), cost, latency, null, reply.requestId());
                p.put("answers", reply.answers());
                span.event(EventType.DECISION, model, "decide " + reply.answers().keySet(), Json.write(p));
                span.ok("answered " + reply.answers().size());
                return result;
            } catch (RuntimeException e) {
                long latency = (System.nanoTime() - start) / 1_000_000;
                usage.record(callRecord(callId, ModelRegistry.Provider.JEV, model, request.callerModule(),
                        request.agentId(), null, null, TokenUsage.ZERO, latency, 0, "FAIL", e.getMessage(), null));
                span.fail(e);
                throw e;
            }
        }
    }

    private static <T> T guarded(CircuitBreaker breaker, Supplier<T> call) {
        try {
            return breaker.executeSupplier(call);
        } catch (CallNotPermittedException e) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "circuit breaker '" + breaker.getName() + "' is open", e);
        }
    }

    private void reportDecision(ChatRequest r, String model, String summary, String detail) {
        monitor.emit(ai.mobiuslab.monitor.MonitorEvent.of(SourceKind.LLM, r.callerModule(), r.agentId(), EventType.LOG, summary)
                .withTarget(model).withMetrics("FAIL", 0, 0).withPayload(Json.write(Map.of("errors", detail))));
    }

    private static Map<String, Object> payload(String callId, String model, String promptKey, Integer promptVersion,
                                               TokenUsage u, double cost, long latency, String finish, String requestId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("callId", callId);
        p.put("model", model);
        p.put("promptKey", promptKey);
        p.put("promptVersion", promptVersion);
        p.put("promptTokens", u.promptTokens());
        p.put("cachedTokens", u.cachedTokens());
        p.put("completionTokens", u.completionTokens());
        p.put("cacheHitRatio", u.cacheHitRatio());
        p.put("costUsd", cost);
        p.put("latencyMs", latency);
        p.put("finishReason", finish);
        p.put("requestId", requestId);
        return p;
    }

    private static LlmCallRepository.CallRecord callRecord(String callId, ModelRegistry.Provider provider, String model,
                                                           String module, String agentId, String promptKey,
                                                           Integer promptVersion, TokenUsage u, long latency,
                                                           double cost, String status, String error, String requestId) {
        return new LlmCallRepository.CallRecord(callId, Instant.now(), provider.name(), model, module, agentId,
                promptKey, promptVersion, u, latency, cost, status, error, requestId);
    }
}
