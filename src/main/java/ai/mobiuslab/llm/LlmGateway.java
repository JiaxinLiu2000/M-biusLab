package ai.mobiuslab.llm;

/**
 * Single entry point for every model call on the platform. Implementations handle provider routing
 * (Crusoe for generation, Jev for decisions), timeouts, retries with backoff on 429/503/529,
 * traffic + cost + cache-hit accounting, and monitor reporting (EventType.LLM_CALL / DECISION).
 */
public interface LlmGateway {

    /** Plain chat completion via Crusoe. */
    ChatResult chat(ChatRequest request);

    /**
     * Chat completion whose content must be JSON valid against {@code jsonSchema} (JSON Schema 2020-12
     * string). Uses response_format json_schema when the model supports it, validates the reply, and on
     * a parse/validation failure re-asks with the validation errors appended, up to 3 retries in total.
     * Throws MobiusException(LLM_BAD_OUTPUT) after the retries are exhausted.
     */
    <T> T chatStructured(ChatRequest request, String jsonSchema, Class<T> type);

    /** Typed decision via Jev (POST /v1/systemone). */
    JevResult decide(JevRequest request);
}
