package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;

import java.util.List;
import java.util.Map;

/**
 * Private / group / public memory for all agents. Default backend: Obsidian vault + Qdrant index.
 * Agents may register additional {@link MemoryBackend}s. All access is permission-checked and
 * reported to the monitor (MEMORY_READ / MEMORY_WRITE).
 */
public interface MemoryService {

    /** Writes a note as {@code agentId}; throws MobiusException(FORBIDDEN) without write access. */
    MemoryNote write(Actor actor, String agentId, MemoryWrite write);

    /** Searches notes the agent can read, best first. */
    List<MemoryHit> search(String agentId, MemoryQuery query);

    /** Reads one note if visible to the agent. */
    MemoryNote get(String agentId, String noteId);

    /** Marks a note invalid (never deletes it); {@code supersededBy} may be null. */
    void invalidate(Actor actor, String agentId, String noteId, String reason, String supersededBy);

    /** Graph of notes and links for visualization; filter keys: scope, groupId, ownerAgentId. */
    GraphView graph(Map<String, String> filter);

    /** Registers an agent-created memory backend under its id. */
    void registerBackend(MemoryBackend backend, Actor actor);

    /** Knowledge-graph view for the 3D graph page. */
    record GraphView(List<GraphNode> nodes, List<GraphLink> links) {
    }

    /** Graph node (one note). */
    record GraphNode(String id, String title, String scope, String groupId, String ownerAgentId, String kind,
                     boolean invalidated) {
    }

    /** Graph edge (one [[link]]). */
    record GraphLink(String source, String target) {
    }
}
