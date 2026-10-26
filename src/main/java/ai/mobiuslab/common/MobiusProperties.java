package ai.mobiuslab.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Typed view of the {@code mobius.*} configuration tree in application.yml. */
@ConfigurationProperties(prefix = "mobius")
public record MobiusProperties(
        String version,
        String home,
        Endpoint crusoe,
        Endpoint jev,
        Qdrant qdrant,
        WebSearch webSearch,
        Monitor monitor,
        Budget budget) {

    /** Remote model endpoint configuration. */
    public record Endpoint(String baseUrl, String apiKey, Duration timeout, int maxRetries) {
        /** True when an API key is configured. */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** Qdrant REST endpoint. */
    public record Qdrant(String url, Duration timeout) {
    }

    /** Optional You.com search key. */
    public record WebSearch(String youApiKey) {
    }

    /** Monitor bus tuning. */
    public record Monitor(int queueCapacity, int batchSize, Duration flushInterval, int recentBuffer) {
    }

    /** Inference budget used for cost warnings. */
    public record Budget(double creditUsd, double warnRatio) {
    }

    /** Absolute platform home directory (workspaces, vault, artifacts). */
    public Path homePath() {
        return Path.of(home).toAbsolutePath().normalize();
    }
}
