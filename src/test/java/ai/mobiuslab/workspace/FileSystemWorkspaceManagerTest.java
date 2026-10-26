package ai.mobiuslab.workspace;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemWorkspaceManagerTest {

    @TempDir
    Path tmp;

    private FileSystemWorkspaceManager manager() {
        return new FileSystemWorkspaceManager(tmp.resolve("workspaces"), WorkspaceTestSupport.monitor(), null);
    }

    @Test
    void rejectsEscapes() {
        FileSystemWorkspaceManager m = manager();
        for (String bad : List.of("../x", "a/../../x", "/etc/passwd", "C:/Windows", "\\\\server\\share")) {
            MobiusException e = assertThrows(MobiusException.class, () -> m.resolve("a1", WorkspaceArea.PRIVATE, bad), bad);
            assertEquals(ErrorCode.FORBIDDEN, e.code());
        }
        assertThrows(MobiusException.class, () -> m.agentRoot("../evil"));
        assertThrows(MobiusException.class, () -> m.withSharedWrite("../x", Duration.ofSeconds(1), () -> 1));
        Path ok = m.resolve("a1", WorkspaceArea.PRIVATE, "sub/./f.txt");
        assertTrue(ok.startsWith(m.agentRoot("a1")));
    }

    @Test
    void ownerResolverSharesWorkspace() {
        FileSystemWorkspaceManager m = new FileSystemWorkspaceManager(tmp.resolve("ws"), WorkspaceTestSupport.monitor(),
                id -> id.equals("b") ? "a" : id);
        assertEquals(m.agentRoot("a"), m.agentRoot("b"));
    }

    @Test
    void writesSameKeyAreSerializedAndListWorks() throws Exception {
        FileSystemWorkspaceManager m = manager();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(pool.submit(() -> m.withSharedWrite("data/x.txt", Duration.ofSeconds(10), () -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    Thread.sleep(100);
                    Path p = m.resolve(null, WorkspaceArea.SHARED, "data/x.txt");
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, "v");
                    active.decrementAndGet();
                    return now;
                })));
            }
            for (Future<Integer> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, maxActive.get());
        assertEquals(0, m.lockEntryCount());
        List<WorkspaceManager.FileEntry> entries = m.list(null, WorkspaceArea.SHARED, "");
        assertEquals(List.of("data", "data/x.txt"), entries.stream().map(WorkspaceManager.FileEntry::path).toList());
    }

    @Test
    void readsOfDifferentPathsRunInParallel() throws Exception {
        FileSystemWorkspaceManager m = manager();
        CountDownLatch both = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> m.withSharedRead("a/1", Duration.ofSeconds(5), () -> {
                both.countDown();
                return both.await(3, TimeUnit.SECONDS);
            }));
            Future<Boolean> b = pool.submit(() -> m.withSharedRead("b/2", Duration.ofSeconds(5), () -> {
                both.countDown();
                return both.await(3, TimeUnit.SECONDS);
            }));
            assertTrue(a.get(10, TimeUnit.SECONDS));
            assertTrue(b.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void writeLockTimeoutThrowsTimeout() throws Exception {
        FileSystemWorkspaceManager m = manager();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> m.withSharedWrite("k.txt", Duration.ofSeconds(5), () -> {
            holding.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }));
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        MobiusException e = assertThrows(MobiusException.class,
                () -> m.withSharedWrite("k.txt", Duration.ofMillis(200), () -> 1));
        assertEquals(ErrorCode.TIMEOUT, e.code());
        // a parent write is blocked by the child writer's parent read lock too
        assertThrows(MobiusException.class, () -> m.withSharedWrite("", Duration.ofMillis(100), () -> 1));
        release.countDown();
        holder.join(5000);
        assertEquals(1, m.withSharedWrite("k.txt", Duration.ofSeconds(1), () -> 1));
    }
}
