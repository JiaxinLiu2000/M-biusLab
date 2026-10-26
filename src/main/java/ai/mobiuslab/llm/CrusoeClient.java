package ai.mobiuslab.llm;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Crusoe OpenAI-compatible chat-completions client (one attempt per call; retries live in the gateway).
 * Blocking java.net.http calls, safe on virtual threads. Enforces a per-model concurrency cap and pauses
 * a model until the next minute when the rate-limit headers report nothing remaining.
 */
@Component
public class CrusoeClient {

    private static final Logger log = LoggerFactory.getLogger(CrusoeClient.class);
    private static final String DEFAULT_BASE = "https://api.inference.crusoecloud.com/v1";

    /** Parsed single completion. */
    public record Reply(String content, String reasoning, String finishReason, TokenUsage usage, String requestId) {
    }

    private final MobiusProperties.Endpoint endpoint;
    private final LlmProperties props;
    private final HttpClient http;
    private final String baseUrl;
    private final Duration defaultTimeout;
    private final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final Map<String, Long> pausedUntil = new ConcurrentHashMap<>();

    /** Creates the client from {@code mobius.crusoe}. */
    @org.springframework.beans.factory.annotation.Autowired
    public CrusoeClient(MobiusProperties mobius, LlmProperties props) {
        this(mobius.crusoe(), props);
    }

    /** Creates the client for an explicit endpoint (tests). */
    public CrusoeClient(MobiusProperties.Endpoint endpoint, LlmProperties props) {
        this.endpoint = endpoint;
        this.props = props;
        String base = endpoint == null || endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                ? DEFAULT_BASE : endpoint.baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.defaultTimeout = endpoint == null || endpoint.timeout() == null ? Duration.ofSeconds(120) : endpoint.timeout();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** True when an API key is configured. */
    public boolean configured() {
        return endpoint != null && endpoint.configured();
    }

    /**
     * Sends one completion.
     *
     * @param responseFormat optional OpenAI response_format object, null for plain text
     */
    public Reply complete(String model, List<ChatMessage> messages, int maxTokens, Double temperature,
                          Map<String, Object> extraBody, Map<String, Object> responseFormat, Duration timeout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> msgs = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            msgs.add(Map.of("role", m.role().name().toLowerCase(Locale.ROOT), "content", m.content()));
        }
        body.put("messages", msgs);
        body.put("max_tokens", maxTokens);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }
        body.putAll(extraBody);
        Duration t = timeout == null ? defaultTimeout : timeout;

        awaitPause(model);
        Semaphore sem = permits.computeIfAbsent(model, k -> new Semaphore(props.maxConcurrencyPerModel(), true));
        try {
            if (!sem.tryAcquire(t.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new MobiusException(ErrorCode.TIMEOUT, "Crusoe concurrency slot for " + model + " not available", true, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "interrupted waiting for Crusoe slot", e);
        }
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(t)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new MobiusException(ErrorCode.TIMEOUT, "Crusoe call to " + model + " timed out after " + t, true, e);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "Crusoe I/O error for " + model + ": " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "interrupted during Crusoe call", e);
        } finally {
            sem.release();
        }
        observeRateLimit(model, resp);
        if (resp.statusCode() / 100 != 2) {
            throw LlmHttpException.forStatus("Crusoe", resp.statusCode(), resp.body());
        }
        return parse(resp.body(), resp.headers().firstValue("x-request-id").orElse(null));
    }

    static Reply parse(String json, String headerRequestId) {
        JsonNode root;
        try {
            root = Json.tree(json);
        } catch (MobiusException e) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "Crusoe returned non-JSON body", true, e);
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String content = text(msg.get("content"));
        String reasoning = text(msg.get("reasoning_content"));
        if (reasoning == null) {
            reasoning = text(msg.get("reasoning"));
        }
        JsonNode u = root.path("usage");
        TokenUsage usage = new TokenUsage(u.path("prompt_tokens").asLong(0),
                u.path("prompt_tokens_details").path("cached_tokens").asLong(0),
                u.path("completion_tokens").asLong(0));
        String id = headerRequestId != null ? headerRequestId : text(root.get("id"));
        return new Reply(content == null ? "" : content, reasoning, text(choice.get("finish_reason")), usage, id);
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private void observeRateLimit(String model, HttpResponse<?> resp) {
        OptionalLong reqs = header(resp, "x-ratelimit-remaining-requests");
        OptionalLong toks = header(resp, "x-ratelimit-remaining-tokens");
        if ((reqs.isPresent() && reqs.getAsLong() <= 0) || (toks.isPresent() && toks.getAsLong() <= 0)) {
            long until = (System.currentTimeMillis() / 60_000 + 1) * 60_000;
            pausedUntil.merge(model, until, Math::max);
            log.warn("Crusoe rate limit exhausted for {} (requests={}, tokens={}); pausing new requests until next minute",
                    model, reqs, toks);
        }
    }

    private static OptionalLong header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).map(v -> {
            try {
                return OptionalLong.of(Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }).orElse(OptionalLong.empty());
    }

    private void awaitPause(String model) {
        Long until = pausedUntil.get(model);
        if (until == null) {
            return;
        }
        long wait = until - System.currentTimeMillis();
        if (wait <= 0) {
            pausedUntil.remove(model, until);
            return;
        }
        log.debug("Crusoe model {} paused by rate limit; waiting {} ms", model, wait);
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "interrupted during rate-limit pause", e);
        }
    }
}
