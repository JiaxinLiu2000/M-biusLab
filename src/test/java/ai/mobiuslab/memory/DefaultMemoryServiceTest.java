package ai.mobiuslab.memory;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.monitor.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static ai.mobiuslab.memory.MemoryTestSupport.note;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultMemoryServiceTest {

    @TempDir
    Path dir;

    private MemoryTestSupport t;
    private DefaultMemoryService svc;

    @BeforeEach
    void setUp() {
        t = new MemoryTestSupport(dir, Map.of(
                "alice", Set.of("PRIVATE", "PUBLIC", "GROUP:g1"),
                "bob", Set.of("PRIVATE", "PUBLIC")));
        svc = t.service;
    }

    private static MemoryQuery q(String text, List<String> groups) {
        return new MemoryQuery(text, null, groups, null, 10, false);
    }

    @Test
    void groupAccessIsEnforced() {
        MemoryNote g = svc.write(Actor.agent("alice"), "alice", note(MemoryScope.GROUP, "g1", "Group fact", "shared lab finding"));
        assertThatThrownBy(() -> svc.get("bob", g.id())).isInstanceOf(MobiusException.class)
                .extracting(e -> ((MobiusException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> svc.write(Actor.agent("bob"), "bob", note(MemoryScope.GROUP, "g1", "x", "y")))
                .extracting(e -> ((MobiusException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(svc.search("bob", q("shared lab finding", List.of("g1")))).isEmpty();
        assertThat(svc.search("alice", q("shared lab finding", List.of("g1")))).extracting(h -> h.note().id()).contains(g.id());
        assertThat(svc.get("alice", g.id()).body()).isEqualTo("shared lab finding");
    }

    @Test
    void privateNotesOfOthersAreInvisible() {
        MemoryNote p = svc.write(Actor.agent("alice"), "alice", note(MemoryScope.PRIVATE, null, "Secret plan", "alpha beta"));
        assertThat(svc.search("bob", q("secret plan alpha", List.of()))).isEmpty();
        assertThatThrownBy(() -> svc.get("bob", p.id())).isInstanceOf(MobiusException.class);
        assertThatThrownBy(() -> svc.invalidate(Actor.agent("bob"), "bob", p.id(), "x", null)).isInstanceOf(MobiusException.class);
        assertThat(svc.search("alice", q("secret plan alpha", List.of()))).hasSize(1);
    }

    @Test
    void invalidatedExcludedByDefaultAndWritesAreIdempotent() {
        MemoryNote a = svc.write(Actor.agent("alice"), "alice", note(MemoryScope.PUBLIC, null, "Boiling point", "water boils at 90C"));
        MemoryNote again = svc.write(Actor.agent("alice"), "alice", note(MemoryScope.PUBLIC, null, "Boiling point", "water boils at 90C"));
        assertThat(again.id()).isEqualTo(a.id());
        svc.invalidate(Actor.agent("alice"), "alice", a.id(), "wrong", null);
        assertThat(svc.search("bob", q("water boils", List.of()))).isEmpty();
        assertThat(svc.search("bob", new MemoryQuery("water boils", null, null, null, 10, true))).hasSize(1);
        assertThat(svc.get("bob", a.id()).invalidated()).isTrue();
        assertThat(t.vault.read(t.store.find(a.id()).orElseThrow().filePath()).invalidated()).isTrue();
        assertThat(t.monitor.recent(500)).extracting(e -> e.type()).contains(EventType.MEMORY_WRITE, EventType.MEMORY_READ);
    }

    @Test
    void compactionInvalidatesOriginals() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(svc.write(Actor.agent("alice"), "alice",
                    note(MemoryScope.PRIVATE, null, "Catalyst note " + i, "catalyst yield observation " + i)).id());
        }
        MemoryNote summary = new MemoryCompactor(svc).compact(Actor.agent("alice"), "alice", "catalyst yield", 3).orElseThrow();
        assertThat(summary.links()).containsExactlyInAnyOrderElementsOf(ids);
        for (String id : ids) {
            MemoryNote n = svc.get("alice", id);
            assertThat(n.invalidated()).isTrue();
            assertThat(n.supersededBy()).isEqualTo(summary.id());
        }
        assertThat(svc.graph(Map.of()).links()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void concurrentWritesProduceIntactNotes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<MemoryNote>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int n = i;
            fs.add(pool.submit(() -> svc.write(Actor.agent("alice"), "alice",
                    note(MemoryScope.PUBLIC, null, "Parallel " + n, "body " + n))));
        }
        for (int i = 0; i < 16; i++) {
            MemoryNote n = fs.get(i).get();
            assertThat(t.vault.read(t.store.find(n.id()).orElseThrow().filePath()).body()).isEqualTo("body " + i);
        }
        pool.shutdown();
        assertThat(t.vault.listFiles()).hasSize(16);
    }
}
