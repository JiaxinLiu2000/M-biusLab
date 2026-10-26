package ai.mobiuslab.common;

/** Platform-wide error codes; each maps to an HTTP status for the API layer. */
public enum ErrorCode {
    VALIDATION(400),
    NOT_FOUND(404),
    CONFLICT(409),
    FORBIDDEN(403),
    SAFETY_DENIED(403),
    NEEDS_USER_DECISION(409),
    LLM_UNAVAILABLE(503),
    LLM_BAD_OUTPUT(502),
    RATE_LIMITED(429),
    TIMEOUT(504),
    EXTERNAL_FAILURE(502),
    SANDBOX_FAILURE(500),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** HTTP status the API layer should return for this code. */
    public int httpStatus() {
        return httpStatus;
    }
}
