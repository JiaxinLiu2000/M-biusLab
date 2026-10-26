# lab

Virtual lab: tools (built-in or agent-written) that agents invoke under concurrency policies, plus a
Docker sandbox for running agent python code.

| Class | Purpose |
|---|---|
| `LabRegistry`, `LabTool`, `LabRequest`, `LabResult`, `ConcurrencyPolicy` | Contract. |
| `DefaultLabRegistry` | Fair `Semaphore` per tool (EXCLUSIVE 1, POOLED n, SHARED unbounded). `invoke`: permission check (optional bean `labPermission`, `BiPredicate<agentId, toolId>`, default allow) → stored result for a finished `requestId` → in-flight dedupe (concurrent duplicates share one execution) → permit with timeout (`queued_ms`) → virtual-thread run with timeout (`ok=false, error="timeout"`, cancelled; permit freed only when the tool really stops) → `lab_call` row. Span `SourceKind.LAB` + `TOOL_CALL` event. Non-SYSTEM `register` needs `SafetyGate.require(LAB_TOOL_REGISTER)`. |
| `LabStore` / `JdbcLabStore` | Persistence of `lab_tool` (audited, RevisionLog) and `lab_call` (append-only, `INSERT IGNORE` on unique `request_id`). |
| `DockerSandbox` | `runPython(agentId, script, args, limits[, mountShared])`: the script source is first submitted to `SafetyGate.require` as a `CODE_EXEC` proposal (denied/escalated scripts never run), then `docker run --rm --network none --memory --cpus --pids-limit 128 --read-only --tmpfs /tmp -v <ws>:/work [-v <shared>:/shared:ro] python:3.12-slim`. Output truncated at 64 KiB, timeout → `docker kill` and status `TIMEOUT`. Docker availability probed once (`SANDBOX_FAILURE` if missing). |
| `PythonRunnerTool` | Built-in `python-runner` (POOLED 2), op `run` `{script, argv, mountShared}`. |
| `DatasetStoreTool` | Built-in `dataset-store` (SHARED), ops `put {path, content}` / `get {path}` / `list {dir}` on `shared/datasets/**` under workspace locks. |
| `AgentWrittenTool` | Agent python script exposed as a tool (EXCLUSIVE by default); runs `python <script> <operation> <argsJson>` in the owner's workspace. |
| `LabBootstrap` | Registers built-ins and restores agent tools on `ApplicationReadyEvent`; `registerAgentTool(agentId, toolId, description, scriptPath, policy)`. |
| `LabProperties` | `mobius.lab.*` (defaultTimeout 5m, sandboxImage, sandboxMemory 512m, sandboxCpus 1.0, sandboxTimeout 120s, pythonRunnerPermits 2). |

Dependencies: `common`, `monitor`, `safety` (lazy `ObjectProvider`), `workspace`. All classes are thread-safe.
