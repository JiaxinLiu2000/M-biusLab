package ai.mobiuslab.genome;

/** Spring application event published whenever an agent's active genome changes; genomeId null = none active. */
public record GenomeActivatedEvent(String agentId, String genomeId) {
}
