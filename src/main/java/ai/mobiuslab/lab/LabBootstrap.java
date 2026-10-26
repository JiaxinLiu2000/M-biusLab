package ai.mobiuslab.lab;

import ai.mobiuslab.common.Actor;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Registers built-in tools at startup, re-hydrates agent tools, and registers new agent-written tools. */
@Component
public class LabBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LabBootstrap.class);

    private final LabRegistry registry;
    private final LabStore store;
    private final DockerSandbox sandbox;
    private final WorkspaceManager workspaces;
    private final LabProperties props;

    /** Spring constructor. */
    public LabBootstrap(LabRegistry registry, LabStore store, DockerSandbox sandbox, WorkspaceManager workspaces,
                        LabProperties props) {
        this.registry = registry;
        this.store = store;
        this.sandbox = sandbox;
        this.workspaces = workspaces;
        this.props = props;
    }

    /** Registers {@code python-runner}, {@code dataset-store} and persisted agent tools once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        registry.register(new PythonRunnerTool(sandbox, props.pythonRunnerPermits()), Actor.SYSTEM);
        registry.register(new DatasetStoreTool(workspaces), Actor.SYSTEM);
        for (LabStore.ToolRow row : store.agentTools()) {
            try {
                registry.register(AgentWrittenTool.fromRow(row, sandbox), Actor.SYSTEM);
            } catch (RuntimeException e) {
                log.warn("Could not restore agent lab tool {}: {}", row.id(), e.toString());
            }
        }
    }

    /**
     * Registers a python script from {@code agentId}'s workspace as a new tool (safety-reviewed as LAB_TOOL_REGISTER).
     * {@code policy} null means EXCLUSIVE.
     */
    public AgentWrittenTool registerAgentTool(String agentId, String toolId, String description, String scriptPath,
                                              ConcurrencyPolicy policy) {
        if (!java.nio.file.Files.isRegularFile(workspaces.resolve(agentId, ai.mobiuslab.workspace.WorkspaceArea.PRIVATE, scriptPath))) {
            throw MobiusException.notFound("tool script", scriptPath);
        }
        AgentWrittenTool tool = new AgentWrittenTool(toolId, description, policy, agentId, scriptPath,
                props.defaultLimits(), sandbox);
        registry.register(tool, Actor.agent(agentId));
        return tool;
    }
}
