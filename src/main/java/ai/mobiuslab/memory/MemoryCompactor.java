package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Merges the top-N similar valid notes on a topic into one summary note that links to the originals,
 * then invalidates the originals (superseded_by = summary). The summarizer is pluggable; the default
 * lists the original titles. Thread-safe as long as the summarizer is.
 */
@Component
public class MemoryCompactor {

    private final DefaultMemoryService memory;
    private volatile Function<List<MemoryNote>, String> summarizer = MemoryCompactor::titleSummary;

    /** Creates the compactor. */
    public MemoryCompactor(DefaultMemoryService memory) {
        this.memory = memory;
    }

    /** Replaces the summarizer (e.g. an LLM-backed one supplied by the master agent). */
    public void setSummarizer(Function<List<MemoryNote>, String> summarizer) {
        this.summarizer = summarizer == null ? MemoryCompactor::titleSummary : summarizer;
    }

    /** Compacts up to {@code maxNotes} writable notes matching the topic; empty when fewer than two qualify. */
    public Optional<MemoryNote> compact(Actor actor, String agentId, String topicQuery, int maxNotes,
                                        List<String> groupIds) {
        List<MemoryNote> notes = memory.search(agentId, new MemoryQuery(topicQuery, null, groupIds, null,
                        Math.max(2, maxNotes) * 2, false)).stream()
                .map(MemoryHit::note)
                .filter(n -> memory.canWrite(agentId, n))
                .limit(Math.max(2, maxNotes))
                .toList();
        if (notes.size() < 2) {
            return Optional.empty();
        }
        MemoryNote first = notes.get(0);
        boolean sameScope = notes.stream().allMatch(n -> ScopeKeys.of(n).equals(ScopeKeys.of(first)));
        MemoryScope scope = sameScope ? first.scope() : MemoryScope.PRIVATE;
        String group = sameScope ? first.groupId() : null;
        List<String> ids = notes.stream().map(MemoryNote::id).toList();
        MemoryNote summary = memory.write(actor, agentId, new MemoryWrite(scope, group, "SUMMARY",
                "Summary: " + topicQuery, summarizer.apply(notes), List.of("compacted"), ids,
                "compaction of " + String.join(",", ids),
                notes.stream().mapToDouble(MemoryNote::confidence).average().orElse(0.5)));
        for (MemoryNote n : notes) {
            memory.invalidate(actor, agentId, n.id(), "compacted into " + summary.id(), summary.id());
        }
        return Optional.of(summary);
    }

    /** Compacts notes visible without group scopes. */
    public Optional<MemoryNote> compact(Actor actor, String agentId, String topicQuery, int maxNotes) {
        return compact(actor, agentId, topicQuery, maxNotes, List.of());
    }

    private static String titleSummary(List<MemoryNote> notes) {
        return notes.stream().map(n -> "- " + n.title()).collect(Collectors.joining("\n"));
    }
}
