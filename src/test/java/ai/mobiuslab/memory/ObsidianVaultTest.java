package ai.mobiuslab.memory;

import ai.mobiuslab.memory.vault.ObsidianVault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ObsidianVaultTest {

    @TempDir
    Path dir;

    private static MemoryNote note(String id, String title, List<String> links) {
        return new MemoryNote(id, MemoryScope.GROUP, "agt_1", "g1", "LESSON", title, "Body of " + title + "\nline 2",
                List.of("a", "b"), links, "run:42", 0.7, Instant.parse("2026-10-17T10:00:00Z"), false, null);
    }

    @Test
    void roundTripWithLinksAndFrontmatter() throws Exception {
        ObsidianVault vault = new ObsidianVault(dir);
        MemoryNote n = note("mem_B", "Learning: Rates & Limits!", List.of("mem_A"));
        String rel = vault.write(n, id -> "other-title-" + id);
        assertThat(rel).isEqualTo("groups/g1/learning-rates-limits-mem_B.md");
        String text = Files.readString(dir.resolve(rel));
        assertThat(text).startsWith("---\n").contains("scope: GROUP").contains("- [[other-title-mem_A]]");
        assertThat(vault.read(rel)).isEqualTo(n);
    }

    @Test
    void concurrentWritesProduceIntactFiles() throws Exception {
        ObsidianVault vault = new ObsidianVault(dir);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            MemoryNote n = note("mem_" + i, "Concurrent note " + i, List.of());
            futures.add(pool.submit(() -> vault.write(n, id -> id)));
        }
        for (int i = 0; i < 16; i++) {
            assertThat(vault.read(futures.get(i).get()).title()).isEqualTo("Concurrent note " + i);
        }
        pool.shutdown();
        assertThat(vault.listFiles()).hasSize(16);
    }
}
