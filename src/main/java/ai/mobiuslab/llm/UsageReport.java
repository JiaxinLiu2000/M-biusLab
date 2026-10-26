package ai.mobiuslab.llm;

import java.time.Instant;
import java.util.List;

/** Read side of LLM traffic accounting: real-time token, cache-hit and cost aggregates. */
public interface UsageReport {

    /** Aggregates since platform start (persisted history is in table llm_call). */
    Snapshot snapshot();

    /**
     * Platform-wide usage snapshot.
     *
     * @param budgetUsd     configured credit (e.g. 50 USD)
     * @param budgetWarning true when costUsd >= budgetUsd * warnRatio
     */
    record Snapshot(Instant since, long calls, long promptTokens, long cachedTokens, long completionTokens,
                    double costUsd, double budgetUsd, boolean budgetWarning, List<Row> byModel, List<Row> byModule,
                    List<Row> byAgent) {
    }

    /** Aggregate for one model / module / agent key. */
    record Row(String key, long calls, long failures, long promptTokens, long cachedTokens, long completionTokens,
               double costUsd, double cacheHitRatio, double avgLatencyMs, long callsLastMinute) {
    }
}
