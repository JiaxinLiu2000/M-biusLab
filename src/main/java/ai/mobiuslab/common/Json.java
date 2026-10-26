package ai.mobiuslab.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared Jackson mapper. Serialization is canonical (sorted keys) so that prompts, genomes and
 * request bodies are byte-identical across calls, which keeps LLM prefix caches warm and hashes stable.
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private Json() {
    }

    /** The shared, thread-safe mapper. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Canonical JSON string of any value. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "JSON serialization failed", e);
        }
    }

    /** Parses JSON into the given type. */
    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new MobiusException(ErrorCode.VALIDATION, "Invalid JSON for " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /** Parses JSON into a generic type. */
    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new MobiusException(ErrorCode.VALIDATION, "Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Parses JSON into a tree. */
    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new MobiusException(ErrorCode.VALIDATION, "Invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** Converts between compatible representations (e.g. Map to record). */
    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /** SHA-256 hex of the canonical JSON of a value; used for genome hashes and idempotency keys. */
    public static String sha256(Object value) {
        return sha256Hex(value instanceof String s ? s : write(value));
    }

    /** SHA-256 hex of a UTF-8 string. */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
