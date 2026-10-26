package ai.mobiuslab.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime settings ({@code mobius.runtime.*}).
 *
 * @param runTimeout        wall-clock limit of one agent run (default 10m)
 * @param duplicateWait     how long a duplicate invocation waits for the original to finish (default 10m)
 * @param duplicatePollInterval DB poll interval while waiting on a run owned by another process (default 500ms)
 */
@ConfigurationProperties(prefix = "mobius.runtime")
public record RuntimeProperties(Duration runTimeout, Duration duplicateWait, Duration duplicatePollInterval) {

    public RuntimeProperties {
        runTimeout = runTimeout == null ? Duration.ofMinutes(10) : runTimeout;
        duplicateWait = duplicateWait == null ? Duration.ofMinutes(10) : duplicateWait;
        duplicatePollInterval = duplicatePollInterval == null ? Duration.ofMillis(500) : duplicatePollInterval;
    }

    /** All defaults. */
    public static RuntimeProperties defaults() {
        return new RuntimeProperties(null, null, null);
    }
}
