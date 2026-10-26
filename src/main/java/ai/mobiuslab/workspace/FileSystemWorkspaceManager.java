package ai.mobiuslab.workspace;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.SourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** File-system backed {@link WorkspaceManager} with path confinement and per-path shared locks. */
@Service
public class FileSystemWorkspaceManager implements WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(FileSystemWorkspaceManager.class);
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final int MAX_LIST = 2000;
    private static final String LOCK_DIR = ".locks";
    private static final long SLOW_LOCK_MS = 1000;

    private final Path agentsBase;
    private final Path sharedBase;
    private final MonitorBus monitor;
    private final Function<String, String> ownerResolver;
    private final ConcurrentHashMap<String, LockRef> locks = new ConcurrentHashMap<>();

    /** Spring constructor; {@code workspaceOwnerResolver} maps agentId to the owning workspace id. */
    @org.springframework.beans.factory.annotation.Autowired
    public FileSystemWorkspaceManager(MobiusProperties props, MonitorBus monitor,
                                      @Qualifier("workspaceOwnerResolver") ObjectProvider<Function<String, String>> ownerResolver) {
        this(props.homePath().resolve("workspaces"), monitor, ownerResolver.getIfAvailable(() -> Function.identity()));
    }

    /** Direct constructor (tests / tools) with an explicit workspaces base directory. */
    public FileSystemWorkspaceManager(Path workspacesBase, MonitorBus monitor, Function<String, String> ownerResolver) {
        Path base = workspacesBase.toAbsolutePath().normalize();
        this.agentsBase = base.resolve("agents");
        this.sharedBase = base.resolve("shared");
        this.monitor = monitor;
        this.ownerResolver = ownerResolver == null ? Function.identity() : ownerResolver;
    }

    /** Per-path lock plus a reference count used to drop idle entries. */
    private static final class LockRef {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        int refs;
    }

    @Override
    public Path agentRoot(String agentId) {
        validateAgentId(agentId);
        String owner = ownerResolver.apply(agentId);
        if (owner == null || owner.isBlank()) {
            owner = agentId;
        }
        validateAgentId(owner);
        return ensureDir(agentsBase.resolve(owner));
    }

    @Override
    public Path sharedRoot() {
        return ensureDir(sharedBase);
    }

    @Override
    public Path resolve(String agentId, WorkspaceArea area, String relativePath) {
        Path root = area == WorkspaceArea.SHARED ? sharedRoot() : agentRoot(agentId);
        return confine(root, relativePath);
    }

    @Override
    public <T> T withSharedRead(String relativePath, Duration timeout, Callable<T> body) {
        String key = normalizeKey(relativePath);
        TreeMap<String, Boolean> plan = new TreeMap<>();
        for (String p : withParents(key)) {
            plan.put(p, false);
        }
        return runLocked(key, plan, timeout, false, body);
    }

    @Override
    public <T> T withSharedWrite(String relativePath, Duration timeout, Callable<T> body) {
        String key = normalizeKey(relativePath);
        TreeMap<String, Boolean> plan = new TreeMap<>();
        for (String p : withParents(key)) {
            plan.put(p, false);
        }
        plan.put(key, true);
        return runLocked(key, plan, timeout, true, body);
    }

    @Override
    public List<FileEntry> list(String agentId, WorkspaceArea area, String relativeDir) {
        Path root = area == WorkspaceArea.SHARED ? sharedRoot() : agentRoot(agentId);
        Path dir = confine(root, relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FileEntry> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> !p.equals(dir))
                    .filter(p -> !root.relativize(p).toString().replace('\\', '/').startsWith(LOCK_DIR))
                    .sorted()
                    .limit(MAX_LIST)
                    .forEach(p -> {
                        try {
                            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            out.add(new FileEntry(root.relativize(p).toString().replace('\\', '/'), a.isDirectory(),
                                    a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis()));
                        } catch (IOException e) {
                            log.debug("Skipping unreadable entry {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot list " + dir, e);
        }
        out.sort((a, b) -> a.path().compareTo(b.path()));
        return out;
    }

    private <T> T runLocked(String key, TreeMap<String, Boolean> plan, Duration timeout, boolean write, Callable<T> body) {
        long deadline = System.nanoTime() + (timeout == null ? Duration.ofMinutes(1) : timeout).toNanos();
        long startWait = System.nanoTime();
        List<Lock> held = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        FileLock fileLock = null;
        FileChannel channel = null;
        try {
            for (Map.Entry<String, Boolean> e : plan.entrySet()) {
                LockRef ref = acquireRef(e.getKey());
                refs.add(e.getKey());
                Lock l = e.getValue() ? ref.lock.writeLock() : ref.lock.readLock();
                long remaining = deadline - System.nanoTime();
                if (!l.tryLock(Math.max(0, remaining), TimeUnit.NANOSECONDS)) {
                    throw new MobiusException(ErrorCode.TIMEOUT, "Timed out waiting for shared " + (write ? "write" : "read")
                            + " lock on '" + key + "' (blocked at '" + e.getKey() + "')", true, null);
                }
                held.add(l);
            }
            if (write) {
                channel = openLockFile(key);
                fileLock = acquireFileLock(channel, key, deadline);
            }
            long waitedMs = (System.nanoTime() - startWait) / 1_000_000;
            if (waitedMs > SLOW_LOCK_MS) {
                log.info("Waited {} ms for shared {} lock on {}", waitedMs, write ? "write" : "read", key);
                monitor.emit(MonitorEvent.of(SourceKind.WORKSPACE, "shared", null, EventType.LOG,
                        "lock wait " + waitedMs + " ms on " + key).withTarget(key).withMetrics("OK", waitedMs, 0));
            }
            long t0 = System.nanoTime();
            T result = body.call();
            if (write) {
                monitor.emit(MonitorEvent.of(SourceKind.WORKSPACE, "shared", null, EventType.DATA_FLOW,
                        "shared write " + key).withTarget(key).withMetrics("OK", (System.nanoTime() - t0) / 1_000_000, 0));
            }
            return result;
        } catch (MobiusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.INTERNAL, "Interrupted while locking " + key, e);
        } catch (Exception e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Shared workspace operation failed on " + key + ": " + e.getMessage(), e);
        } finally {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException e) {
                    log.warn("Failed to release file lock for {}: {}", key, e.toString());
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
            for (String r : refs) {
                releaseRef(r);
            }
        }
    }

    private FileChannel openLockFile(String key) throws IOException {
        Path dir = ensureDir(sharedRoot().resolve(LOCK_DIR));
        Path file = dir.resolve(Json.sha256Hex(key).substring(0, 32) + ".lock");
        return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private FileLock acquireFileLock(FileChannel channel, String key, long deadline) throws IOException, InterruptedException {
        while (true) {
            try {
                FileLock l = channel.tryLock();
                if (l != null) {
                    return l;
                }
            } catch (OverlappingFileLockException e) {
                // held by this JVM via another channel; wait as for a foreign process
            }
            if (System.nanoTime() >= deadline) {
                throw new MobiusException(ErrorCode.TIMEOUT, "Timed out waiting for file lock on '" + key + "'", true, null);
            }
            Thread.sleep(20);
        }
    }

    private LockRef acquireRef(String key) {
        return locks.compute(key, (k, v) -> {
            LockRef r = v == null ? new LockRef() : v;
            r.refs++;
            return r;
        });
    }

    private void releaseRef(String key) {
        locks.computeIfPresent(key, (k, v) -> --v.refs <= 0 ? null : v);
    }

    /** Number of live lock entries (for tests / diagnostics). */
    int lockEntryCount() {
        return locks.size();
    }

    private static List<String> withParents(String key) {
        List<String> out = new ArrayList<>();
        out.add("");
        int idx = 0;
        while ((idx = key.indexOf('/', idx)) >= 0) {
            out.add(key.substring(0, idx));
            idx++;
        }
        out.remove(key);
        return out;
    }

    private static String normalizeKey(String relativePath) {
        checkRelative(relativePath);
        String rel = relativePath == null ? "" : relativePath.replace('\\', '/');
        Path p = Path.of(rel).normalize();
        if (p.startsWith("..")) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Path escapes workspace: " + relativePath);
        }
        return p.toString().replace('\\', '/');
    }

    private static void checkRelative(String relativePath) {
        if (relativePath == null) {
            return;
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains(":")
                || relativePath.indexOf('\0') >= 0 || Path.of(relativePath.replace('\0', '_')).isAbsolute()) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Absolute paths are not allowed: " + relativePath);
        }
    }

    private static Path confine(Path root, String relativePath) {
        checkRelative(relativePath);
        String rel = relativePath == null ? "" : relativePath.replace('\\', '/');
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Path escapes workspace: " + relativePath);
        }
        try {
            Path realRoot = root.toRealPath();
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw new MobiusException(ErrorCode.FORBIDDEN, "Path resolves outside workspace via link: " + relativePath);
            }
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Cannot verify path " + relativePath + ": " + e.getMessage(), e);
        }
        return target;
    }

    private static void validateAgentId(String agentId) {
        if (agentId == null || !AGENT_ID.matcher(agentId).matches() || agentId.equals(".") || agentId.equals("..")) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "Invalid agent id for workspace: " + agentId);
        }
    }

    private static Path ensureDir(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot create directory " + dir, e);
        }
    }
}
