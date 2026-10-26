package ai.mobiuslab.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Gateway tuning under {@code mobius.llm.*}; every value has an in-code default so no YAML is required.
 *
 * @param defaultMaxTokens      max_tokens when a request leaves it null
 * @param maxConcurrencyPerModel in-flight Crusoe requests allowed per model
 * @param resultCacheTtl        idempotent result cache TTL (temperature == 0 requests)
 * @param resultCacheSize       idempotent result cache max entries
 * @param backoffBase           first retry delay
 * @param backoffCap            max retry delay
 * @param structuredRetries     repair re-asks after the first structured attempt
 * @param usageQueueCapacity    bounded queue of llm_call rows awaiting persistence
 * @param usageBatchSize        rows per batch insert
 * @param usageFlushInterval    max wait before a partial batch is flushed
 */
@ConfigurationProperties(prefix = "mobius.llm")
public record LlmProperties(
        Integer defaultMaxTokens,
        Integer maxConcurrencyPerModel,
        Duration resultCacheTtl,
        Integer resultCacheSize,
        Duration backoffBase,
        Duration backoffCap,
        Integer structuredRetries,
        Integer usageQueueCapacity,
        Integer usageBatchSize,
        Duration usageFlushInterval) {

    public LlmProperties {
        defaultMaxTokens = defaultMaxTokens == null ? 2048 : defaultMaxTokens;
        maxConcurrencyPerModel = maxConcurrencyPerModel == null ? 16 : maxConcurrencyPerModel;
        resultCacheTtl = resultCacheTtl == null ? Duration.ofMinutes(10) : resultCacheTtl;
        resultCacheSize = resultCacheSize == null ? 2000 : resultCacheSize;
        backoffBase = backoffBase == null ? Duration.ofSeconds(1) : backoffBase;
        backoffCap = backoffCap == null ? Duration.ofSeconds(30) : backoffCap;
        structuredRetries = structuredRetries == null ? 3 : structuredRetries;
        usageQueueCapacity = usageQueueCapacity == null ? 10_000 : usageQueueCapacity;
        usageBatchSize = usageBatchSize == null ? 200 : usageBatchSize;
        usageFlushInterval = usageFlushInterval == null ? Duration.ofSeconds(1) : usageFlushInterval;
    }

    /** All defaults. */
    public static LlmProperties defaults() {
        return new LlmProperties(null, null, null, null, null, null, null, null, null, null);
    }
}
