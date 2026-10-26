package ai.mobiuslab.lab;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.workspace.WorkspaceArea;
import ai.mobiuslab.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Built-in {@code dataset-store}: lock-protected text datasets under {@code shared/datasets/**}. */
public final class DatasetStoreTool implements LabTool {

    /** Tool id. */
    public static final String ID = "dataset-store";
    private static final String PREFIX = "datasets";
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_READ = 4L * 1024 * 1024;

    private final WorkspaceManager workspaces;

    /** Creates the tool. */
    public DatasetStoreTool(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Shared dataset store. Operations: put {path, content}, get {path}, list {dir}. Paths are relative "
                + "to shared/datasets; writes are serialized per path.";
    }

    @Override
    public ConcurrencyPolicy policy() {
        return new ConcurrencyPolicy(ConcurrencyPolicy.Kind.SHARED, 0);
    }

    @Override
    public LabResult invoke(LabRequest request) {
        Map<String, Object> args = request.args() == null ? Map.of() : request.args();
        String op = request.operation() == null ? "" : request.operation();
        return switch (op) {
            case "put" -> put(rel(args.get("path")), String.valueOf(args.getOrDefault("content", "")));
            case "get" -> get(rel(args.get("path")));
            case "list" -> list(args.get("dir") == null ? PREFIX : rel(args.get("dir")));
            default -> throw MobiusException.invalid("dataset-store operation must be put/get/list, got " + op);
        };
    }

    private LabResult put(String rel, String content) {
        Path target = workspaces.resolve(null, WorkspaceArea.SHARED, rel);
        long size = workspaces.withSharedWrite(rel, LOCK_TIMEOUT, () -> {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return Files.size(target);
        });
        return new LabResult(true, Map.of("path", rel, "bytes", size), null, 0, null);
    }

    private LabResult get(String rel) {
        Path target = workspaces.resolve(null, WorkspaceArea.SHARED, rel);
        String content = workspaces.withSharedRead(rel, LOCK_TIMEOUT, () -> {
            if (!Files.isRegularFile(target)) {
                throw MobiusException.notFound("dataset", rel);
            }
            if (Files.size(target) > MAX_READ) {
                throw new MobiusException(ErrorCode.VALIDATION, "Dataset too large to read via tool: " + rel);
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        });
        return new LabResult(true, Map.of("path", rel, "content", content), null, 0, null);
    }

    private LabResult list(String rel) {
        List<WorkspaceManager.FileEntry> entries = workspaces.withSharedRead(rel, LOCK_TIMEOUT,
                () -> workspaces.list(null, WorkspaceArea.SHARED, rel));
        return new LabResult(true, Map.of("dir", rel, "entries", entries), null, 0, null);
    }

    private static String rel(Object path) {
        if (!(path instanceof String s) || s.isBlank()) {
            throw MobiusException.invalid("dataset-store needs a path");
        }
        String p = s.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith(PREFIX + "/")) {
            p = p.substring(PREFIX.length() + 1);
        }
        if (p.startsWith("/") || p.contains(":")) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "dataset path must be relative: " + s);
        }
        String full = Path.of(PREFIX, p).normalize().toString().replace('\\', '/');
        if (!full.equals(PREFIX) && !full.startsWith(PREFIX + "/")) {
            throw new MobiusException(ErrorCode.FORBIDDEN, "dataset path escapes shared/datasets: " + s);
        }
        return full;
    }
}
