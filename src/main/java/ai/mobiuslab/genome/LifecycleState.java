package ai.mobiuslab.genome;

/** Lifecycle of a genome version: created, active, under evaluation, kept, retired or rolled back. */
public enum LifecycleState {
    DRAFT,
    ACTIVE,
    EVALUATING,
    RETAINED,
    RETIRED,
    ROLLED_BACK
}
