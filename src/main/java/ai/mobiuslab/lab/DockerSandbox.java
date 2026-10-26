package ai.mobiuslab.lab;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Ids;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.monitor.EventType;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.SourceKind;
import ai.mobiuslab.monitor.Span;
import ai.mobiuslab.workspace.WorkspaceArea;
import ai.mobiuslab.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import ai.mobiuslab.common.Actor;
import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyGate;
import org.springframework.beans.factory.ObjectProvider;

/** Runs agent python scripts inside locked-down, network-less Docker containers. */
@Service
public class DockerSandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);
    private static final int MAX_OUTPUT = 64 * 1024;

    private final WorkspaceManager workspaces;
    private final MonitorBus monitor;
    private final LabProperties props;
    private volatile Boolean dockerAvailable;

    /** Container limits. */
    public record SandboxLimits(String memory, double cpus, Duration timeout) {
        public SandboxLimits {
            memory = memory == null || memory.isBlank() ? "512m" : memory;
            cpus = cpus <= 0 ? 1.0 : cpus;
            timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        }

        /** 512m / 1 CPU / 120 s. */
        public static SandboxLimits defaults() {
            return new SandboxLimits(null, 1.0, null);
        }
    }

    /** Outcome of one sandbox run; status is OK, ERROR (non-zero exit) or TIMEOUT. */
    public record SandboxResult(int exitCode, String stdout, String stderr, long durationMs, String status) {
    }

    private static final int MAX_REVIEWED_SOURCE = 20_000;

    private final ObjectProvider<SafetyGate> safety;

    /** Spring constructor; every script is reviewed by the safety gate (CODE_EXEC) before it runs. */
    @org.springframework.beans.factory.annotation.Autowired
    public DockerSandbox(WorkspaceManager workspaces, MonitorBus monitor, LabProperties props,
                         ObjectProvider<SafetyGate> safety) {
        this.workspaces = workspaces;
        this.monitor = monitor;
        this.props = props;
        this.safety = safety;
    }

    /** Constructor without safety review (tests only). */
    public DockerSandbox(WorkspaceManager workspaces, MonitorBus monitor, LabProperties props) {
        this(workspaces, monitor, props, null);
    }

    /** Runs {@code relativeScriptPath} (inside the agent workspace) with python in a fresh container. */
    public SandboxResult runPython(String agentId, String relativeScriptPath, List<String> args, SandboxLimits limits) {
        return runPython(agentId, relativeScriptPath, args, limits, false);
    }

    /** As {@link #runPython(String, String, List, SandboxLimits)}, optionally mounting the shared workspace read-only at /shared. */
    public SandboxResult runPython(String agentId, String relativeScriptPath, List<String> args, SandboxLimits limits,
                                   boolean mountShared) {
        SandboxLimits lim = limits == null ? props.defaultLimits() : limits;
        Path script = workspaces.resolve(agentId, WorkspaceArea.PRIVATE, relativeScriptPath);
        if (!Files.isRegularFile(script)) {
            throw MobiusException.notFound("sandbox script", relativeScriptPath);
        }
        reviewScript(agentId, relativeScriptPath, script);
        ensureDocker();
        Path root = workspaces.agentRoot(agentId);
        String containerScript = root.relativize(script).toString().replace('\\', '/');
        String name = "mobius-sbx-" + Ids.newId("x").replaceAll("[^A-Za-z0-9_.-]", "").toLowerCase();
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name, "--network", "none",
                "--memory", lim.memory(), "--cpus", String.valueOf(lim.cpus()), "--pids-limit", "128",
                "--read-only", "--tmpfs", "/tmp", "-v", dockerPath(root) + ":/work"));
        if (mountShared) {
            cmd.addAll(List.of("-v", dockerPath(workspaces.sharedRoot()) + ":/shared:ro"));
        }
        cmd.addAll(List.of("-w", "/work", props.sandboxImage(), "python", "-u", containerScript));
        if (args != null) {
            cmd.addAll(args);
        }
        try (Span span = monitor.start(SourceKind.LAB, "sandbox", agentId, "python " + containerScript)) {
            span.event(EventType.TOOL_CALL, name, "docker run " + containerScript, null);
            SandboxResult r = exec(cmd, name, lim.timeout());
            log.info("Sandbox {} for {} finished: {} exit {} in {} ms", name, agentId, r.status(), r.exitCode(), r.durationMs());
            if ("OK".equals(r.status())) {
                span.ok("exit 0 in " + r.durationMs() + " ms");
            } else {
                span.fail(new MobiusException(ErrorCode.SANDBOX_FAILURE, r.status() + " exit " + r.exitCode()));
            }
            return r;
        }
    }

    /** Submits the script source as a CODE_EXEC proposal; throws SAFETY_DENIED / NEEDS_USER_DECISION when refused. */
    private void reviewScript(String agentId, String relativePath, Path script) {
        SafetyGate gate = safety == null ? null : safety.getIfAvailable();
        if (gate == null) {
            return;
        }
        String source;
        try {
            source = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.SANDBOX_FAILURE, "cannot read script " + relativePath, e);
        }
        if (source.length() > MAX_REVIEWED_SOURCE) {
            source = source.substring(0, MAX_REVIEWED_SOURCE) + "\n# ...[truncated for review]";
        }
        gate.require(ActionProposal.of(Actor.agent(agentId), ActionKind.CODE_EXEC, relativePath,
                "run python script in offline sandbox (no network, read-only root, workspace mounted)", source));
    }

    private SandboxResult exec(List<String> cmd, String name, Duration timeout) {
        long t0 = System.nanoTime();
        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.SANDBOX_FAILURE, "Cannot start docker: " + e.getMessage(), e);
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // no stdin needed
        }
        CompletableFuture<String> out = drain(p.getInputStream());
        CompletableFuture<String> err = drain(p.getErrorStream());
        try {
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String status;
            if (!finished) {
                log.warn("Sandbox {} exceeded {}; killing", name, timeout);
                kill(name);
                if (!p.waitFor(15, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                status = "TIMEOUT";
            } else {
                status = p.exitValue() == 0 ? "OK" : "ERROR";
            }
            int exit = p.isAlive() ? -1 : p.exitValue();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new SandboxResult(exit, out.get(10, TimeUnit.SECONDS), err.get(10, TimeUnit.SECONDS), ms, status);
        } catch (InterruptedException e) {
            kill(name);
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MobiusException(ErrorCode.SANDBOX_FAILURE, "Sandbox interrupted: " + name, e);
        } catch (Exception e) {
            throw new MobiusException(ErrorCode.SANDBOX_FAILURE, "Sandbox output failed for " + name + ": " + e, e);
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static CompletableFuture<String> drain(InputStream in) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            boolean truncated = false;
            try {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    int room = MAX_OUTPUT - buf.size();
                    if (room > 0) {
                        buf.write(chunk, 0, Math.min(room, n));
                    }
                    if (n > room) {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                // stream closed on kill
            } finally {
                close(in);
            }
            String s = buf.toString(StandardCharsets.UTF_8);
            f.complete(truncated ? s + "\n...[truncated]" : s);
        });
        return f;
    }

    private void kill(String name) {
        try {
            Process k = new ProcessBuilder("docker", "kill", name).redirectErrorStream(true).start();
            k.getInputStream().readAllBytes();
            k.waitFor(15, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.warn("docker kill {} failed: {}", name, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureDocker() {
        Boolean ok = dockerAvailable;
        if (ok == null) {
            synchronized (this) {
                if (dockerAvailable == null) {
                    dockerAvailable = probeDocker();
                }
                ok = dockerAvailable;
            }
        }
        if (!ok) {
            throw new MobiusException(ErrorCode.SANDBOX_FAILURE, "Docker is not available (docker version failed)");
        }
    }

    private static boolean probeDocker() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            boolean ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) {
                p.destroyForcibly();
                log.error("Docker daemon not reachable; python sandbox disabled");
            }
            return ok;
        } catch (IOException e) {
            log.error("Docker CLI not found; python sandbox disabled: {}", e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Host path in the form Docker accepts for {@code -v} (forward slashes, works on Docker Desktop for Windows). */
    static String dockerPath(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
