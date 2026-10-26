package ai.mobiuslab.memory;

import java.time.Instant;
import java.util.List;

/**
 * A memory entry, persisted as an Obsidian note (Markdown + YAML frontmatter + [[links]]) and indexed
 * in Qdrant. Notes are append-only: corrections create a new note and invalidate the old one.
 *
 * @param id           note id ("mem_...")
 * @param scope        visibility
 * @param ownerAgentId author / owner agent
 * @param groupId      group for GROUP scope, else null
 * @param kind         FACT, LESSON, SKILL, OBSERVATION, PLAN, RESULT, ...
 * @param title        note title (also the Obsidian file name stem)
 * @param body         Markdown body
 * @param tags         tags
 * @param links        ids of linked notes (rendered as [[title]] links)
 * @param source       provenance (URL, run id, file, agent message id)
 * @param confidence   0..1
 * @param createdAt    creation time
 * @param invalidated  true when superseded or refuted
 * @param supersededBy id of the replacing note, nullable
 */
public record MemoryNote(String id, MemoryScope scope, String ownerAgentId, String groupId, String kind,
                         String title, String body, List<String> tags, List<String> links, String source,
                         double confidence, Instant createdAt, boolean invalidated, String supersededBy) {
}
