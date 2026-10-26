package ai.mobiuslab.lab;

/**
 * A virtual-lab instrument: built-in software or software written by agents. Implementations need
 * not be thread-safe beyond what their {@link ConcurrencyPolicy} declares; the registry enforces it.
 */
public interface LabTool {

    /** Unique tool id. */
    String id();

    /** What the tool does and which operations/args it accepts (shown to agents). */
    String description();

    /** Declared concurrency policy. */
    ConcurrencyPolicy policy();

    /** Executes one request. */
    LabResult invoke(LabRequest request);
}
