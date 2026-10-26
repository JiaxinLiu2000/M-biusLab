package ai.mobiuslab.genome;

import ai.mobiuslab.common.Actor;

import java.util.List;

/** Versioned storage of agent genomes: save (deduped), activate, rollback, lineage and score queries. */
public interface GenomeService {

    /** Saves a new version; assigns an id if null and returns the existing genome when the content hash matches. */
    Genome save(Genome draft, Actor actor, String reason);

    /** Genome by id; throws NOT_FOUND. */
    Genome get(String genomeId);

    /** Active genome of an agent; throws NOT_FOUND when none is active. */
    Genome active(String agentId);

    /** Makes a genome the active version of its agent (previous active becomes RETAINED). */
    void activate(String genomeId, Actor actor, String reason);

    /** Reactivates the parent of the active genome and marks the current one ROLLED_BACK. */
    Genome rollback(String agentId, Actor actor, String reason);

    /** Sets the lifecycle state (ACTIVE delegates to {@link #activate}). */
    void setState(String genomeId, LifecycleState s, Actor actor, String reason);

    /** Records an objective score into the genome's score summary (count, average, last). */
    void recordScore(String genomeId, String objectiveId, double score);

    /** All genomes of a lineage ordered by generation. */
    List<Genome> lineage(String lineageId);

    /** All genomes of an agent ordered by generation. */
    List<Genome> history(String agentId);
}
