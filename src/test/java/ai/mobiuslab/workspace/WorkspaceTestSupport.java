package ai.mobiuslab.workspace;

import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.monitor.AsyncMonitorBus;
import ai.mobiuslab.monitor.MonitorBus;
import ai.mobiuslab.monitor.MonitorEvent;
import ai.mobiuslab.monitor.MonitorRepository;

import java.time.Duration;
import java.util.List;

/** Test helper: a real in-memory monitor bus that never touches a database. */
public final class WorkspaceTestSupport {

    private WorkspaceTestSupport() {
    }

    /** Monitor bus whose persistence is a no-op. */
    public static MonitorBus monitor() {
        MobiusProperties props = new MobiusProperties("test", ".", null, null, null, null,
                new MobiusProperties.Monitor(10_000, 100, Duration.ofMillis(200), 1000), null);
        MonitorRepository repo = new MonitorRepository(null) {
            @Override
            public void insertBatch(List<MonitorEvent> events) {
                // no database in unit tests
            }
        };
        return new AsyncMonitorBus(repo, props);
    }
}
