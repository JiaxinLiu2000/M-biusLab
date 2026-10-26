package ai.mobiuslab.llm;

import ai.mobiuslab.common.MobiusProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Real-time LLM traffic accounting: lock-free aggregates by model / module / agent plus asynchronous
 * batch persistence of every call to {@code llm_call} through a bounded queue drained by one thread.
 */
@Component
public class UsageMeter implements UsageReport {

    private static final Logger log = LoggerFactory.getLogger(UsageMeter.class);

    private final LlmCallRepository repository;
    private final double budgetUsd;
    private final double warnRatio;
    private final int batchSize;
    private final long flushMillis;
    private final Instant since = Instant.now();
    private final Agg total = new Agg();
    private final Map<String, Agg> byModel = new ConcurrentHashMap<>();
    private final Map<String, Agg> byModule = new ConcurrentHashMap<>();
    private final Map<String, Agg> byAgent = new ConcurrentHashMap<>();
    private final BlockingQueue<LlmCallRepository.CallRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean budgetWarned = new AtomicBoolean();
    private final Thread writer;
    private volatile boolean running = true;

    /** Creates the meter and starts its writer thread. */
    public UsageMeter(LlmCallRepository repository, MobiusProperties mobius, LlmProperties props) {
        this.repository = repository;
        MobiusProperties.Budget budget = mobius.budget();
        this.budgetUsd = budget == null ? 50.0 : budget.creditUsd();
        this.warnRatio = budget == null || budget.warnRatio() <= 0 ? 0.8 : budget.warnRatio();
        this.batchSize = props.usageBatchSize();
        this.flushMillis = props.usageFlushInterval().toMillis();
        this.queue = new ArrayBlockingQueue<>(props.usageQueueCapacity());
        this.writer = Thread.ofPlatform().name("llm-usage-writer").daemon(true).start(this::drainLoop);
    }

    /** Accounts one call (success or failure) and queues it for persistence. Never throws. */
    public void record(LlmCallRepository.CallRecord r) {
        try {
            boolean ok = "OK".equals(r.status());
            long now = System.currentTimeMillis();
            total.add(r, ok, now);
            byModel.computeIfAbsent(r.model(), k -> new Agg()).add(r, ok, now);
            byModule.computeIfAbsent(r.callerModule(), k -> new Agg()).add(r, ok, now);
            byAgent.computeIfAbsent(r.agentId() == null ? "(platform)" : r.agentId(), k -> new Agg()).add(r, ok, now);
            if (budgetUsd > 0 && total.cost.sum() >= budgetUsd * warnRatio && budgetWarned.compareAndSet(false, true)) {
                log.warn("LLM spend {} USD reached {}% of the {} USD credit", String.format("%.4f", total.cost.sum()),
                        Math.round(warnRatio * 100), budgetUsd);
            }
            if (!queue.offer(r)) {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 1000 == 0) {
                    log.warn("llm_call queue full; {} rows dropped from persistence so far", n);
                }
            }
        } catch (RuntimeException e) {
            log.error("usage accounting failed for call {}: {}", r.callId(), e.toString());
        }
    }

    @Override
    public Snapshot snapshot() {
        long now = System.currentTimeMillis();
        double cost = total.cost.sum();
        return new Snapshot(since, total.calls.sum(), total.prompt.sum(), total.cached.sum(), total.completion.sum(),
                cost, budgetUsd, budgetUsd > 0 && cost >= budgetUsd * warnRatio,
                rows(byModel, now), rows(byModule, now), rows(byAgent, now));
    }

    private static List<Row> rows(Map<String, Agg> map, long now) {
        List<Row> out = new ArrayList<>(map.size());
        map.forEach((k, a) -> out.add(a.row(k, now)));
        out.sort(Comparator.comparingDouble(Row::costUsd).reversed().thenComparing(Row::key));
        return out;
    }

    /** Flushes queued rows on shutdown. */
    @PreDestroy
    public void shutdown() {
        running = false;
        writer.interrupt();
        try {
            writer.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<LlmCallRepository.CallRecord> rest = new ArrayList<>();
        queue.drainTo(rest);
        flush(rest);
    }

    private void drainLoop() {
        List<LlmCallRepository.CallRecord> batch = new ArrayList<>(batchSize);
        while (running) {
            try {
                LlmCallRepository.CallRecord first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    flush(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flush(List<LlmCallRepository.CallRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.insertBatch(batch);
        } catch (RuntimeException e) {
            log.error("llm_call batch insert failed ({} rows lost): {}", batch.size(), e.getMessage());
        }
    }

    /** Thread-safe aggregate for one key. */
    private static final class Agg {
        final LongAdder calls = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder prompt = new LongAdder();
        final LongAdder cached = new LongAdder();
        final LongAdder completion = new LongAdder();
        final LongAdder latency = new LongAdder();
        final DoubleAdder cost = new DoubleAdder();
        final ConcurrentLinkedDeque<Long> recent = new ConcurrentLinkedDeque<>();

        void add(LlmCallRepository.CallRecord r, boolean ok, long now) {
            calls.increment();
            if (!ok) {
                failures.increment();
            }
            prompt.add(r.usage().promptTokens());
            cached.add(r.usage().cachedTokens());
            completion.add(r.usage().completionTokens());
            latency.add(r.latencyMs());
            cost.add(r.costUsd());
            recent.addLast(now);
            prune(now);
        }

        void prune(long now) {
            Long head;
            while ((head = recent.peekFirst()) != null && head < now - 60_000) {
                recent.pollFirst();
            }
        }

        Row row(String key, long now) {
            prune(now);
            long n = calls.sum();
            long p = prompt.sum();
            long c = cached.sum();
            return new Row(key, n, failures.sum(), p, c, completion.sum(), cost.sum(),
                    p == 0 ? 0 : (double) c / p, n == 0 ? 0 : (double) latency.sum() / n, recent.size());
        }
    }
}
