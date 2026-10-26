package ai.mobiuslab.lab;

import ai.mobiuslab.common.MobiusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in {@code python-runner}: runs a script from the caller's workspace in the Docker sandbox. */
public final class PythonRunnerTool implements LabTool {

    /** Tool id. */
    public static final String ID = "python-runner";

    private final DockerSandbox sandbox;
    private final ConcurrencyPolicy policy;

    /** Creates the tool with POOLED({@code permits}) concurrency. */
    public PythonRunnerTool(DockerSandbox sandbox, int permits) {
        this.sandbox = sandbox;
        this.policy = ConcurrencyPolicy.pooled(Math.max(1, permits));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Runs a python script from your workspace in an offline sandbox. Operation 'run', args: "
                + "{script: relative path, argv: [strings], mountShared: bool (shared workspace read-only at /shared)}. "
                + "Returns exitCode, stdout, stderr, status.";
    }

    @Override
    public ConcurrencyPolicy policy() {
        return policy;
    }

    @Override
    public LabResult invoke(LabRequest request) {
        if (request.operation() != null && !"run".equals(request.operation())) {
            throw MobiusException.invalid("python-runner supports only 'run', got " + request.operation());
        }
        if (request.agentId() == null) {
            throw MobiusException.invalid("python-runner needs an agentId");
        }
        Map<String, Object> args = request.args() == null ? Map.of() : request.args();
        Object script = args.get("script");
        if (!(script instanceof String s) || s.isBlank()) {
            throw MobiusException.invalid("python-runner needs args.script");
        }
        List<String> argv = new ArrayList<>();
        if (args.get("argv") instanceof List<?> l) {
            l.forEach(o -> argv.add(String.valueOf(o)));
        }
        boolean mountShared = Boolean.TRUE.equals(args.get("mountShared"));
        return toResult(sandbox.runPython(request.agentId(), s, argv, null, mountShared));
    }

    /** Maps a sandbox result to a lab result. */
    static LabResult toResult(DockerSandbox.SandboxResult r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exitCode", r.exitCode());
        data.put("stdout", r.stdout());
        data.put("stderr", r.stderr());
        data.put("status", r.status());
        boolean ok = "OK".equals(r.status());
        String error = ok ? null : "TIMEOUT".equals(r.status()) ? "timeout" : "exit code " + r.exitCode();
        return new LabResult(ok, data, r.stdout() + (r.stderr().isEmpty() ? "" : "\n[stderr]\n" + r.stderr()),
                r.durationMs(), error);
    }
}
