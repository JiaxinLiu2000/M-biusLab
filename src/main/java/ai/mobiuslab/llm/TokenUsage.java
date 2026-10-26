package ai.mobiuslab.llm;

/** Token counts of one call; cachedTokens is the prefix-cache hit portion of promptTokens. */
public record TokenUsage(long promptTokens, long cachedTokens, long completionTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    /** Cache hit ratio of the prompt (0 when no prompt tokens). */
    public double cacheHitRatio() {
        return promptTokens == 0 ? 0 : (double) cachedTokens / promptTokens;
    }
}
