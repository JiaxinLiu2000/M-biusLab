package ai.mobiuslab.memory;

import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.genome.Access;
import ai.mobiuslab.memory.index.QdrantIndex;
import ai.mobiuslab.memory.store.MemoryNoteStore;
import ai.mobiuslab.memory.vault.ObsidianVault;
import ai.mobiuslab.monitor.AsyncMonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.MonitorRepository;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a memory service wired to a temp vault, an in-memory store and a degraded Qdrant. */
final class MemoryTestSupport {

    final ObsidianVault vault;
    final MemoryNoteStore store;
    final ObsidianQdrantBackend backend;
    final AsyncMonitorBus monitor;
    final DefaultMemoryService service;

    /** Per-agent access: agentId -> scope keys with READ_WRITE (PRIVATE/PUBLIC/GROUP:g). */
    MemoryTestSupport(Path dir, Map<String, Set<String>> grants, MemoryNoteStore store, QdrantIndex index) {
        Embedder embedder = new HashingEmbedder();
        this.vault = new ObsidianVault(dir.resolve("vault"));
        this.store = store;
        this.backend = new ObsidianQdrantBackend(vault, store, index, embedder, MemoryProperties.defaults());
        this.monitor = monitorBus();
        MemoryAccessPolicy policy = (agent, scope, group) ->
                grants.getOrDefault(agent, Set.of()).contains(scope.key(group)) ? Access.READ_WRITE : Access.NONE;
        this.service = new DefaultMemoryService(backend, new EpisodicLogBackend(dir.resolve("episodic")), store, policy,
                monitor, MemoryProperties.defaults(), () -> null, a -> null);
    }

    MemoryTestSupport(Path dir, Map<String, Set<String>> grants) {
        this(dir, grants, new InMemoryNoteStore(),
                new QdrantIndex("http://127.0.0.1:1", "unused", Duration.ofSeconds(1), new HashingEmbedder()));
    }

    static AsyncMonitorBus monitorBus() {
        MobiusProperties props = new MobiusProperties("0", ".", null, null, null, null,
                new MobiusProperties.Monitor(10_000, 100, Duration.ofSeconds(1), 5_000), null);
        return new AsyncMonitorBus(new MonitorRepository(null) {
            @Override
            public void insertBatch(List<MonitorEvent> events) {
            }
        }, props);
    }

    static MemoryWrite note(MemoryScope scope, String group, String title, String body) {
        return new MemoryWrite(scope, group, "FACT", title, body, List.of("t"), List.of(), "test", 0.8);
    }
}
