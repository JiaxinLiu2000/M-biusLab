package ai.mobiuslab.safety;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence of safety verdicts, idempotent by proposal id. */
public interface VerdictStore {

    /** A stored verdict row for listing. */
    record StoredVerdict(long id, String actor, ActionKind kind, String target, SafetyVerdict verdict, Instant createdAt) {
    }

    /** Stored verdict for a proposal, if reviewed before. */
    Optional<SafetyVerdict> find(String proposalId);

    /** Saves a verdict; when another verdict for the same proposal already exists, returns that one. */
    SafetyVerdict saveIfAbsent(ActionProposal proposal, SafetyVerdict verdict);

    /** Newest-first page with id < cursor (null cursor = from the newest). */
    List<StoredVerdict> page(Long cursor, int limit);
}
