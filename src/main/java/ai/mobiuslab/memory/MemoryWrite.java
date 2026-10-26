package ai.mobiuslab.memory;

import java.util.List;

/** Input for writing a new memory note. */
public record MemoryWrite(MemoryScope scope, String groupId, String kind, String title, String body,
                          List<String> tags, List<String> links, String source, double confidence) {
}
