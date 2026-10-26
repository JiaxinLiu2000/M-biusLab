# runtime

Executes an agent's genome graph once, idempotently per invocation id.

| Class | Purpose |
|---|---|
| `AgentRunner` / `DefaultAgentRunner` | Idempotent on `invocationId` (`agent_run` unique key; finished → stored result; concurrent duplicates wait, in-process via futures, cross-process via DB polling). One `SourceKind.AGENT` span per run; all exceptions mapped to FAILED / TIMEOUT (run deadline) / DENIED (FORBIDDEN, SAFETY_DENIED, NEEDS_USER_DECISION). |
| `GraphExecutor` | DAG validation (cycle → VALIDATION), topological order, conditional edges `key == value` / `key != value`, skipping of inactive branches, one `SourceKind.NODE` span per node (`agentId/geneId`), `monitor.flow` per active edge, per-node timeout (`config.timeoutMs`, default 120000) on virtual threads (run under the node span via `Span.callWithParent`, so nested spans share the invocation trace), `config.onError` = `fail` / `skip` / `fallback` (`config.fallback`). Output = merged OUTPUT node values, else last node. |
| Sealed runs | Input key `AgentInvocation.SEALED_KEY` (`"__sealed"`) = true: the key is stripped before nodes see the input, `monitor.flow` payloads are null and span summaries read `sealed`, and `agent_run.input`/`output` store `{"sealed":true}` (an idempotent replay therefore returns that marker, not the real output). |
| `Node`, `NodeOutput`, `NodeContext` | Node SPI. |
| `CodeFunction`, `CodeFunctionRegistry`, `BuiltinCodeFunctions` | Named Java functions for CODE nodes: `text.template`, `json.pick`, `collect.merge`, `score.threshold`. |
| `AgentRunRepository` | `agent_run` log (`V8__runtime.sql`). |
| `RuntimeProperties` | `mobius.runtime.run-timeout` (10m), `duplicate-wait` (10m), `duplicate-poll-interval` (500ms). |
| `node.*` | INPUT/OUTPUT pass-through, LLM (PromptLibrary + LlmGateway, default vars `role`/`description` from the genome and `input` = JSON of the node input unless input or `config.vars` override, `config.jsonSchema` → structured), JEV_DECISION (`config.questions` → flattened answers `{id, id_confidence}` over the input), CODE, TOOL/LAB (permission check → LabRegistry), MEMORY_READ/WRITE. |

Dependencies: `GenomeService`, `LlmGateway`, `PromptLibrary`, `MemoryService`, `LabRegistry`, `MonitorBus`, `ObjectProvider<AgentRunner>` (sub-agent calls).
Threading: beans are stateless; each node runs on its own virtual thread; in-flight runs tracked in a `ConcurrentHashMap`.
