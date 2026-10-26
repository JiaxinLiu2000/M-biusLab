package ai.mobiuslab.memory;

import ai.mobiuslab.memory.index.QdrantIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ensures the Qdrant collection at startup and periodically re-indexes rows marked {@code index_pending}. */
@Component
public class MemoryReindexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryReindexer.class);

    private final QdrantIndex index;
    private final ObsidianQdrantBackend backend;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Creates the reindexer. */
    public MemoryReindexer(QdrantIndex index, ObsidianQdrantBackend backend) {
        this.index = index;
        this.backend = backend;
    }

    /** Creates the collection once the app is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        index.ensureCollection();
    }

    /** Retries pending index writes (non-overlapping runs). */
    @Scheduled(fixedDelayString = "${mobius.memory.reindex-interval-ms:30000}", initialDelay = 30000)
    public void reindex() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            backend.reindexPending();
        } catch (RuntimeException e) {
            log.warn("Memory reindex run failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
