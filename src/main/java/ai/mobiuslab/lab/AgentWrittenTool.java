package ai.mobiuslab.lab;

import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter exposing a python script from an agent's workspace as a {@link LabTool}. The script runs in
 * the owner's workspace through {@link DockerSandbox} as {@code python <script> <operation> <argsJson>}
 * and should print its result (JSON if possible) to stdout.
 */
public final class AgentWrittenTool implements LabTool {

    private final String id;
    private final String description;
    private final ConcurrencyPolicy policy;
    private final String ownerAgentId;
    private final String scriptPath;
    private final DockerSandbox.SandboxLimits limits;
    private final DockerSandbox sandbox;

    /** Creates an adapter; {@code policy} defaults to EXCLUSIVE and {@code limits} to sandbox defaults. */
    public AgentWrittenTool(String id, String description, ConcurrencyPolicy policy, String ownerAgentId,
                            String scriptPath, DockerSandbox.SandboxLimits limits, DockerSandbox sandbox) {
        if (id == null || ownerAgentId == null || scriptPath == null) {
            throw MobiusException.invalid("Agent tool needs id, owner and script path");
        }
        this.id = id;
        this.description = description == null ? "" : description;
        this.policy = policy == null ? ConcurrencyPolicy.exclusive() : policy;
        this.ownerAgentId = ownerAgentId;
        this.scriptPath = scriptPath;
        this.limits = limits == null ? DockerSandbox.SandboxLimits.defaults() : limits;
        this.sandbox = sandbox;
    }

    /** Re-creates an adapter from its persisted row. */
    public static AgentWrittenTool fromRow(LabStore.ToolRow row, DockerSandbox sandbox) {
        Map<String, Object> s = row.spec() == null ? Map.of() : row.spec();
        Object mem = s.get("memory");
        Object cpus = s.get("cpus");
        Object timeout = s.get("timeoutSeconds");
        DockerSandbox.SandboxLimits limits = new DockerSandbox.SandboxLimits(mem == null ? null : mem.toString(),
                cpus instanceof Number n ? n.doubleValue() : 1.0,
                timeout instanceof Number n ? Duration.ofSeconds(n.longValue()) : null);
        return new AgentWrittenTool(row.id(), row.description(), row.policy(), row.ownerAgentId(),
                String.valueOf(s.get("scriptPath")), limits, sandbox);
    }

    /** Owning agent id. */
    public String ownerAgentId() {
        return ownerAgentId;
    }

    /** Persisted spec (script path and limits). */
    public Map<String, Object> spec() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scriptPath", scriptPath);
        m.put("memory", limits.memory());
        m.put("cpus", limits.cpus());
        m.put("timeoutSeconds", limits.timeout().toSeconds());
        return m;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public ConcurrencyPolicy policy() {
        return policy;
    }

    @Override
    public LabResult invoke(LabRequest request) {
        String op = request.operation() == null ? "run" : request.operation();
        String argsJson = Json.write(request.args() == null ? Map.of() : request.args());
        DockerSandbox.SandboxResult r = sandbox.runPython(ownerAgentId, scriptPath, List.of(op, argsJson), limits);
        return PythonRunnerTool.toResult(r);
    }
}
