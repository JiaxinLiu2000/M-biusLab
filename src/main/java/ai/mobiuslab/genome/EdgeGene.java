package ai.mobiuslab.genome;

/**
 * Directed data edge inside an agent graph. {@code condition} is null (always) or
 * "outputKey == value" evaluated on the source node output (used for Jev-driven branching).
 */
public record EdgeGene(String from, String to, String condition) {
}
