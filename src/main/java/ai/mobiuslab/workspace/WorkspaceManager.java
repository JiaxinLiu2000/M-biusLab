package ai.mobiuslab.workspace;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Per-agent isolated workspaces plus one shared workspace under MOBIUS_HOME/workspaces. All paths are
 * confined (no escaping via .. or symlinks). Shared-workspace access is serialized by per-path
 * read/write locks (in-process) plus a file lock (cross-process).
 */
public interface WorkspaceManager {

    /** Root of an agent's private workspace (created on demand; honours WorkspaceSpec.SHARED_WITH). */
    Path agentRoot(String agentId);

    /** Root of the shared workspace. */
    Path sharedRoot();

    /** Resolves a relative path inside an area; throws MobiusException(FORBIDDEN) on escape attempts. */
    Path resolve(String agentId, WorkspaceArea area, String relativePath);

    /** Runs {@code body} holding a shared read lock on {@code relativePath} (and its parents). */
    <T> T withSharedRead(String relativePath, Duration timeout, Callable<T> body);

    /** Runs {@code body} holding an exclusive write lock on {@code relativePath}. */
    <T> T withSharedWrite(String relativePath, Duration timeout, Callable<T> body);

    /** Lists files of an area for the file browser. */
    List<FileEntry> list(String agentId, WorkspaceArea area, String relativeDir);

    /** One file-browser entry. */
    record FileEntry(String path, boolean directory, long size, long modifiedEpochMs) {
    }
}
