package ai.mobiuslab.lab;

import ai.mobiuslab.workspace.FileSystemWorkspaceManager;
import ai.mobiuslab.workspace.WorkspaceTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class DockerSandboxIntegrationTest {

    @TempDir
    Path tmp;

    @Test
    void runsHelloAndKillsInfiniteLoop() throws Exception {
        FileSystemWorkspaceManager ws = new FileSystemWorkspaceManager(tmp, WorkspaceTestSupport.monitor(), null);
        DockerSandbox sandbox = new DockerSandbox(ws, WorkspaceTestSupport.monitor(), LabProperties.defaults());
        Files.writeString(ws.agentRoot("a1").resolve("hello.py"), "import sys\nprint('hello', *sys.argv[1:])\n");
        Files.writeString(ws.agentRoot("a1").resolve("loop.py"), "while True:\n    pass\n");

        DockerSandbox.SandboxResult hello = sandbox.runPython("a1", "hello.py", List.of("world"),
                new DockerSandbox.SandboxLimits("256m", 1.0, Duration.ofSeconds(180)));
        assertEquals("OK", hello.status(), hello.stderr());
        assertTrue(hello.stdout().contains("hello world"));

        DockerSandbox.SandboxResult loop = sandbox.runPython("a1", "loop.py", List.of(),
                new DockerSandbox.SandboxLimits("256m", 0.5, Duration.ofSeconds(5)));
        assertEquals("TIMEOUT", loop.status());
    }
}
