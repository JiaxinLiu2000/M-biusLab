package ai.mobiuslab.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter for unstable third-party calls. Retries only {@link MobiusException}s
 * marked retryable; everything else propagates immediately.
 */
public final class Backoff {

    private static final Logger log = LoggerFactory.getLogger(Backoff.class);

    private final int maxAttempts;
    private final Duration base;
    private final Duration cap;

    public Backoff(int maxAttempts, Duration base, Duration cap) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.base = base;
        this.cap = cap;
    }

    /** Default policy: 4 attempts, 1s base, 30s cap (Crusoe-recommended shape). */
    public static Backoff standard() {
        return new Backoff(4, Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /** Runs the call, retrying retryable failures; {@code label} is used for logging. */
    public <T> T call(String label, Callable<T> call) {
        MobiusException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (MobiusException e) {
                if (!e.retryable() || attempt == maxAttempts) {
                    throw e;
                }
                last = e;
                long sleepMs = delayMs(attempt);
                log.warn("{} failed (attempt {}/{}), retrying in {} ms: {}", label, attempt, maxAttempts, sleepMs, e.getMessage());
                sleep(sleepMs);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new MobiusException(ErrorCode.EXTERNAL_FAILURE, label + " failed: " + e.getMessage(), e);
            }
        }
        throw last;
    }

    /** Delay before the next attempt: min(cap, base * 2^(attempt-1)) + up to 500 ms jitter. */
    long delayMs(int attempt) {
        long exp = base.toMillis() * (1L << Math.min(attempt - 1, 20));
        return Math.min(cap.toMillis(), exp) + ThreadLocalRandom.current().nextLong(0, 500);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "Interrupted during backoff", e);
        }
    }
}
