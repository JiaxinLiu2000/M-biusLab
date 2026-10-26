# monitor

The single reporting channel for every agent, node and module (start / end / fail of work, messages,
data flows, tool calls, memory access, LLM calls, safety verdicts, evolution decisions).

| Class | Purpose |
|---|---|
| `MonitorBus` | Contract: `emit`, `start` (span), `flow` (data moving between components), `subscribe`, `recent`, `stats`. |
| `AsyncMonitorBus` | Default implementation: bounded queue + one writer thread batch-inserting into `monitor_event`; in-memory ring buffer; synchronous fan-out to listeners (SSE). Never throws to callers. |
| `Span` | Try-with-resources unit of work; emits `TASK_START` and exactly one `TASK_END`/`TASK_FAIL`; nests via a thread-local stack and propagates `traceId`/`agentId` into the logging MDC. `Span.callWithParent(parent, body)` runs work on another thread (e.g. a node's virtual thread) under the parent span so nested spans share its trace. |
| `MonitorStats` | Lock-free rolling stats per source (calls in the last minute, totals, failures, average latency) for "per-module calls/min and latency". |
| `MonitorRepository` | Batch insert (`INSERT IGNORE`, idempotent on `event_id`) and keyset-paged queries. |
| `MonitorEvent`, `EventType`, `SourceKind` | Event model. |

Usage:
```java
try (Span span = monitor.start(SourceKind.NODE, agentId + "/" + geneId, agentId, "llm node")) {
    monitor.flow(SourceKind.NODE, agentId + "/n1", agentId + "/n2", agentId, "e1", payload);
    span.ok("done");
}
```
Backpressure: when the queue is full events still reach live listeners but are not persisted (`droppedCount()`).
