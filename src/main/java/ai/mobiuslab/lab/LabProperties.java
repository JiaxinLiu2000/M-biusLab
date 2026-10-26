package ai.mobiuslab.lab;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Lab and sandbox settings ({@code mobius.lab.*}); every value has an in-code default.
 *
 * @param defaultTimeout   tool call timeout (and queue wait) when the request gives none
 * @param sandboxImage     Docker image for python sandboxes
 * @param sandboxMemory    default container memory limit
 * @param sandboxCpus      default container CPU limit
 * @param sandboxTimeout   default sandbox wall-clock timeout
 * @param pythonRunnerPermits concurrent python-runner calls
 */
@ConfigurationProperties(prefix = "mobius.lab")
public record LabProperties(Duration defaultTimeout, String sandboxImage, String sandboxMemory, Double sandboxCpus,
                            Duration sandboxTimeout, Integer pythonRunnerPermits) {

    public LabProperties {
        defaultTimeout = defaultTimeout == null ? Duration.ofMinutes(5) : defaultTimeout;
        sandboxImage = sandboxImage == null || sandboxImage.isBlank() ? "python:3.12-slim" : sandboxImage;
        sandboxMemory = sandboxMemory == null || sandboxMemory.isBlank() ? "512m" : sandboxMemory;
        sandboxCpus = sandboxCpus == null ? 1.0 : sandboxCpus;
        sandboxTimeout = sandboxTimeout == null ? Duration.ofSeconds(120) : sandboxTimeout;
        pythonRunnerPermits = pythonRunnerPermits == null ? 2 : pythonRunnerPermits;
    }

    /** All defaults. */
    public static LabProperties defaults() {
        return new LabProperties(null, null, null, null, null, null);
    }

    /** Default sandbox limits derived from these settings. */
    public DockerSandbox.SandboxLimits defaultLimits() {
        return new DockerSandbox.SandboxLimits(sandboxMemory, sandboxCpus, sandboxTimeout);
    }
}
