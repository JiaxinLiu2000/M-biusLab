package ai.mobiuslab.llm;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;

/** Provider HTTP failure carrying the status code and (truncated) response body. */
public class LlmHttpException extends MobiusException {

    private final int status;
    private final String body;

    /** Creates the exception; retryability is derived by the caller from the status. */
    public LlmHttpException(ErrorCode code, String message, boolean retryable, int status, String body) {
        super(code, message, retryable, null);
        this.status = status;
        this.body = body == null ? "" : body;
    }

    /** HTTP status returned by the provider. */
    public int status() {
        return status;
    }

    /** Response body (may be empty). */
    public String body() {
        return body;
    }

    /** Maps a non-2xx status to an exception: 429/5xx(incl. 529) retryable, auth/validation errors not. */
    static LlmHttpException forStatus(String provider, int status, String body) {
        String snippet = body == null ? "" : (body.length() > 500 ? body.substring(0, 500) : body);
        String msg = provider + " HTTP " + status + ": " + snippet;
        if (status == 429) {
            return new LlmHttpException(ErrorCode.RATE_LIMITED, msg, true, status, body);
        }
        if (status >= 500) {
            return new LlmHttpException(ErrorCode.LLM_UNAVAILABLE, msg, true, status, body);
        }
        if (status == 401 || status == 403) {
            return new LlmHttpException(ErrorCode.LLM_UNAVAILABLE, msg, false, status, body);
        }
        return new LlmHttpException(ErrorCode.EXTERNAL_FAILURE, msg, false, status, body);
    }
}
