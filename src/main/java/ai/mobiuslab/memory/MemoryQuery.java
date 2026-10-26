package ai.mobiuslab.memory;

import java.util.List;

/** Semantic memory search restricted to scopes/groups/kinds the caller may read. */
public record MemoryQuery(String text, List<MemoryScope> scopes, List<String> groupIds, List<String> kinds,
                          int limit, boolean includeInvalidated) {
}
