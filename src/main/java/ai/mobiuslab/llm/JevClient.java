package ai.mobiuslab.llm;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** TypeSafe System One (Jev) client: POST {base}/v1/systemone; one attempt per call. */
@Component
public class JevClient {

    /** Default model alias. */
    public static final String DEFAULT_MODEL = "jev-latest";
    private static final String DEFAULT_BASE = "https://api.typesafe.ai";

    /** Parsed Jev response. */
    public record Reply(String model, Map<String, JevAnswer> answers, TokenUsage usage, String requestId) {
    }

    private final MobiusProperties.Endpoint endpoint;
    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    /** Creates the client from {@code mobius.jev}. */
    @Autowired
    public JevClient(MobiusProperties mobius) {
        this(mobius.jev());
    }

    /** Creates the client for an explicit endpoint (tests). */
    public JevClient(MobiusProperties.Endpoint endpoint) {
        this.endpoint = endpoint;
        String base = endpoint == null || endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                ? DEFAULT_BASE : endpoint.baseUrl();
        this.baseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.timeout = endpoint == null || endpoint.timeout() == null ? Duration.ofSeconds(15) : endpoint.timeout();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** True when an API key is configured. */
    public boolean configured() {
        return endpoint != null && endpoint.configured();
    }

    /** Sends one decision request. */
    public Reply decide(String model, Object state, Map<String, JevQuestion> questions) {
        Map<String, Object> qs = new LinkedHashMap<>();
        questions.forEach((id, q) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", q.type());
            m.put("instructions", q.instructions());
            Object criteria = switch (q) {
                case JevQuestion.Noul n -> n.criteria();
                case JevQuestion.Choice c -> c.criteria();
                case JevQuestion.Score s -> s.criteria();
            };
            if (criteria != null) {
                m.put("criteria", criteria);
            }
            qs.put(id, m);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", model);
        body.put("questions", qs);
        HttpResponse<String> resp;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/systemone"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new MobiusException(ErrorCode.TIMEOUT, "Jev call timed out after " + timeout, true, e);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "Jev I/O error: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "interrupted during Jev call", e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw LlmHttpException.forStatus("Jev", resp.statusCode(), resp.body());
        }
        return parse(resp.body(), resp.headers().firstValue("x-typesafe-request-id").orElse(null));
    }

    static Reply parse(String json, String requestId) {
        JsonNode root;
        try {
            root = Json.tree(json);
        } catch (MobiusException e) {
            throw new MobiusException(ErrorCode.LLM_UNAVAILABLE, "Jev returned non-JSON body", true, e);
        }
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.path("answers").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            answers.put(e.getKey(), answer(e.getValue()));
        }
        JsonNode u = root.path("usage");
        TokenUsage usage = new TokenUsage(u.path("input_tokens").asLong(0), 0, u.path("output_tokens").asLong(0));
        return new Reply(root.path("model").asText(null), answers, usage, requestId);
    }

    private static JevAnswer answer(JsonNode a) {
        String type = a.path("type").asText("");
        Map<String, Double> probs = null;
        if (a.has("probabilities")) {
            probs = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = a.get("probabilities").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> p = it.next();
                probs.put(p.getKey(), p.getValue().asDouble());
            }
        }
        Double confidence = num(a, "confidence");
        return switch (type) {
            case "noul" -> new JevAnswer(type, num(a, "noul"), null, null, probs, confidence);
            case "choice" -> new JevAnswer(type, null, a.path("choice").asText(null), null, probs, confidence);
            case "score" -> new JevAnswer(type, null, null, num(a, "score"), probs, confidence);
            default -> throw new MobiusException(ErrorCode.LLM_BAD_OUTPUT, "unknown Jev answer type '" + type + "'");
        };
    }

    private static Double num(JsonNode a, String field) {
        JsonNode n = a.get(field);
        return n == null || !n.isNumber() ? null : n.asDouble();
    }
}
