package ai.mobiuslab.memory.index;

import ai.mobiuslab.common.Backoff;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.memory.Embedder;
import ai.mobiuslab.memory.MemoryNote;
import ai.mobiuslab.memory.MemoryProperties;
import ai.mobiuslab.memory.ScopeKeys;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant REST client for the memory vector index. Visibility is enforced with payload filters on
 * {@code scope_key}/{@code invalidated}. If Qdrant is unreachable the index is "degraded": callers fall
 * back to MySQL search and mark rows for reindexing. Thread-safe (immutable client + volatile flag).
 */
@Component
public class QdrantIndex {

    private static final Logger log = LoggerFactory.getLogger(QdrantIndex.class);

    /** One vector search hit. */
    public record Hit(String noteId, double score) {
    }

    private final String baseUrl;
    private final String collection;
    private final Duration timeout;
    private final Embedder embedder;
    private final HttpClient http;
    private final Backoff backoff = new Backoff(3, Duration.ofMillis(200), Duration.ofSeconds(2));
    private volatile boolean ready;

    /** Spring constructor. */
    @Autowired
    public QdrantIndex(MobiusProperties mobius, MemoryProperties props, Embedder embedder) {
        this(mobius.qdrant() == null || mobius.qdrant().url() == null ? "http://localhost:6333" : mobius.qdrant().url(),
                props.collection(),
                mobius.qdrant() == null || mobius.qdrant().timeout() == null ? Duration.ofSeconds(10) : mobius.qdrant().timeout(),
                embedder);
    }

    /** Explicit constructor (tests). */
    public QdrantIndex(String baseUrl, String collection, Duration timeout, Embedder embedder) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.collection = collection;
        this.timeout = timeout;
        this.embedder = embedder;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** True when the collection is known to exist and the last call succeeded. */
    public boolean ready() {
        return ready;
    }

    /** Creates the collection and payload indexes if missing; on failure logs WARN and stays degraded. */
    public boolean ensureCollection() {
        try {
            HttpResponse<String> r = send("GET", "/collections/" + collection, null, true);
            if (r.statusCode() == 404) {
                send("PUT", "/collections/" + collection,
                        Map.of("vectors", Map.of("size", embedder.dimension(), "distance", "Cosine")), false);
                log.info("Created Qdrant collection {} (dim {})", collection, embedder.dimension());
            }
            for (String f : List.of("scope", "owner", "group", "kind", "scope_key")) {
                send("PUT", "/collections/" + collection + "/index?wait=true",
                        Map.of("field_name", f, "field_schema", "keyword"), false);
            }
            send("PUT", "/collections/" + collection + "/index?wait=true",
                    Map.of("field_name", "invalidated", "field_schema", "bool"), false);
            ready = true;
        } catch (RuntimeException e) {
            ready = false;
            log.warn("Qdrant unavailable at {} ({}); memory search degraded to MySQL fallback", baseUrl, e.getMessage());
        }
        return ready;
    }

    /** Point id derived from the note id. */
    public static String pointId(String noteId) {
        return UUID.nameUUIDFromBytes(noteId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Upserts the note's vector and payload. Throws MobiusException when Qdrant fails. */
    public void upsert(MemoryNote note) {
        requireReady();
        float[] vec = embedder.embed(note.title() + "\n" + (note.body() == null ? "" : note.body()));
        Map<String, Object> point = Map.of("id", pointId(note.id()), "vector", vec, "payload", payload(note));
        send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", List.of(point)), false);
    }

    /** Updates invalidation flags in the payload of an existing point. */
    public void markInvalidated(String noteId, String supersededBy) {
        requireReady();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("invalidated", true);
        if (supersededBy != null) {
            p.put("superseded_by", supersededBy);
        }
        send("POST", "/collections/" + collection + "/points/payload?wait=true",
                Map.of("payload", p, "points", List.of(pointId(noteId))), false);
    }

    /** Vector search restricted to the given scope keys and kinds. */
    public List<Hit> search(String text, List<String> allowedScopeKeys, List<String> kinds, boolean includeInvalidated,
                            int limit) {
        requireReady();
        if (allowedScopeKeys.isEmpty()) {
            return List.of();
        }
        List<Object> must = new ArrayList<>();
        must.add(Map.of("key", "scope_key", "match", Map.of("any", allowedScopeKeys)));
        if (!includeInvalidated) {
            must.add(Map.of("key", "invalidated", "match", Map.of("value", false)));
        }
        if (kinds != null && !kinds.isEmpty()) {
            must.add(Map.of("key", "kind", "match", Map.of("any", kinds)));
        }
        Map<String, Object> body = Map.of("vector", embedder.embed(text), "limit", Math.max(1, limit),
                "with_payload", true, "filter", Map.of("must", must));
        JsonNode res = Json.tree(send("POST", "/collections/" + collection + "/points/search", body, false).body());
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : res.path("result")) {
            hits.add(new Hit(h.path("payload").path("noteId").asText(), h.path("score").asDouble()));
        }
        return hits;
    }

    private static Map<String, Object> payload(MemoryNote n) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("noteId", n.id());
        p.put("scope", n.scope().name());
        p.put("scope_key", ScopeKeys.of(n));
        p.put("owner", n.ownerAgentId());
        if (n.groupId() != null) {
            p.put("group", n.groupId());
        }
        p.put("kind", n.kind());
        p.put("invalidated", n.invalidated());
        p.put("title", n.title());
        return p;
    }

    private void requireReady() {
        if (!ready) {
            throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, "Qdrant index degraded");
        }
    }

    private HttpResponse<String> send(String method, String path, Object body, boolean allow404) {
        return backoff.call("qdrant " + method + " " + path, () -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                    .header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(body)));
            HttpResponse<String> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                throw new MobiusException(ErrorCode.TIMEOUT, "Qdrant timeout " + path, true, e);
            } catch (IOException e) {
                throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, "Qdrant I/O " + path + ": " + e.getMessage(), true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MobiusException(ErrorCode.INTERNAL, "Interrupted calling Qdrant", e);
            }
            int sc = r.statusCode();
            if (sc == 404 && allow404) {
                return r;
            }
            if (sc >= 500 || sc == 429) {
                throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, "Qdrant " + sc + " " + path + ": " + r.body(), true, null);
            }
            if (sc >= 300) {
                throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, "Qdrant " + sc + " " + path + ": " + r.body());
            }
            return r;
        });
    }

    /** Marks the index degraded after a failed call (re-enabled by {@link #ensureCollection()}). */
    public void markDegraded() {
        ready = false;
    }
}
