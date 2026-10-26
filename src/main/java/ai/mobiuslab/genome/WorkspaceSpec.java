package ai.mobiuslab.genome;

/** Workspace arrangement: an isolated private workspace, or sharing another agent's workspace. */
public record WorkspaceSpec(Mode mode, String shareWithAgentId) {

    /** Workspace mode. */
    public enum Mode { ISOLATED, SHARED_WITH }

    /** Default isolated workspace. */
    public static WorkspaceSpec isolated() {
        return new WorkspaceSpec(Mode.ISOLATED, null);
    }
}
