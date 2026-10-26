package ai.mobiuslab.common;

/** Base runtime exception carrying an {@link ErrorCode} and whether a retry may succeed. */
public class MobiusException extends RuntimeException {

    private final ErrorCode code;
    private final boolean retryable;

    public MobiusException(ErrorCode code, String message) {
        this(code, message, false, null);
    }

    public MobiusException(ErrorCode code, String message, Throwable cause) {
        this(code, message, false, cause);
    }

    public MobiusException(ErrorCode code, String message, boolean retryable, Throwable cause) {
        super(code.name() + ": " + message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** Error category. */
    public ErrorCode code() {
        return code;
    }

    /** True when the operation may succeed if retried (rate limit, transient outage, timeout). */
    public boolean retryable() {
        return retryable;
    }

    /** Shortcut for a missing entity. */
    public static MobiusException notFound(String what, String id) {
        return new MobiusException(ErrorCode.NOT_FOUND, what + " not found: " + id);
    }

    /** Shortcut for a validation failure. */
    public static MobiusException invalid(String message) {
        return new MobiusException(ErrorCode.VALIDATION, message);
    }
}
