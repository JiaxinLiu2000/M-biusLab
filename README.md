# Möbius Lab

A platform for self-improving research agents. An agent's whole design (its node graph, prompts,
model choice, permissions, memory and workspace settings) is stored as a versioned **genome**. The
goal is a loop in which the agent proposes an edit to its own genome, an independent **referee**
scores the edited version on locked criteria, and the edit is **kept or rolled back** based on that
score.

## Current status

We are in the early stage of the project. We have set up a sandbox where agent-written code can run
safely, and have a first version of the runtime that runs a single agent on the task. The
keep-or-roll-back loop and the referee are what we are building now, so there are no results to
report yet.

| Component | Status |
|---|---|
| Sandbox for agent-written code (Docker, workspaces, lab tools, safety gate) | Done |
| Runtime: single agent executing its genome graph on the task | First version done |
| LLM gateway and prompt library | Done (used by the runtime) |
| Keep-or-roll-back loop | In progress |
| Referee (locked criteria, held-out tasks) | In progress |

See [PROGRESS.md](PROGRESS.md) for the step-by-step log.

## What exists today

### Sandbox
Agent-written code never runs on the host. `lab.DockerSandbox` runs it in a throwaway
`python:3.12-slim` container:

```
docker run --rm --network none --memory 512m --cpus 1 --pids-limit 128 -v <workspace>:/work ...
```

- No network, capped memory/CPU/processes, a hard timeout (the container is killed), truncated output.
- Each agent gets a confined workspace (`workspace.FileSystemWorkspaceManager`); path escapes are
  rejected, shared files use read/write + file locks.
- Code reaches the sandbox only through registered lab tools (`python-runner`, `dataset-store`)
  via `lab.DefaultLabRegistry` (concurrency limits, idempotent request ids, timeouts, per-genome
  permissions that fail closed).
- Every code execution passes `safety.SafetyGate` first: hard rules (dangerous shell commands,
  path escapes, protected paths, leaked keys, unapproved hosts), then a fast model screen, then a
  fail-closed LLM review for anything risky.

### Runtime (single agent)
- `genome`: genome storage with content-hash dedupe, versions, lineage and activation.
- `runtime.GraphExecutor`: runs the genome's node graph in topological order with conditional
  edges, per-node timeouts and failure policies (fail / skip / fallback). Node kinds: INPUT, OUTPUT,
  LLM, JEV_DECISION, CODE, TOOL, LAB, MEMORY_READ, MEMORY_WRITE.
- `runtime.DefaultAgentRunner`: runs one agent once per invocation id (idempotent), records time,
  cost and output in `agent_run`.
- `memory`: per-agent notes in an Obsidian-style vault indexed in Qdrant, with scope-based access.
- `llm` / `prompt`: Crusoe and Jev clients with backoff, circuit breakers, schema-validated
  structured output (3 retries), usage and cost metering; versioned prompt templates.
- `monitor`: every node, tool call, memory access and LLM call is traced through `MonitorBus`.

### Task
The current task is binary promoter classification on a balanced subset of Genomic Benchmarks
(`demo/promoters/tasks.jsonl`, Apache-2.0, see [LICENSE-NOTICE](demo/promoters/LICENSE-NOTICE.md)).

## Planned (not built yet)

- **Keep-or-roll-back loop**: reflect on a run, propose a bounded edit to the genome, safety-review
  it, evaluate it, keep it if it beats the parent on the referee's score, otherwise roll back.
- **Referee**: criteria locked before the loop starts, held-out tasks stored only on the referee
  side, only aggregate scores returned to the agent.

Any expected effects of the loop are predictions at this stage, not measured results.

## Getting started

Requirements: JDK 21+, Maven 3.9+, Docker.

```bash
cp .env.example .env          # add CRUSOE_API_KEY / TYPESAFE_API_KEY for real model calls
docker compose up -d          # MySQL on 3307, Qdrant on 6333
mvn -q compile
mvn test                      # unit tests (no external services)
mvn spring-boot:run           # applies Flyway migrations, health at /actuator/health
mvn -Pintegration test        # Docker sandbox, MySQL and Qdrant tests (run the app once first)
```

## Layout

| Package | Role |
|---|---|
| `common` | ids, errors, JSON, backoff, configuration, revision log |
| `monitor` | tracing and event bus |
| `llm`, `prompt` | model gateway, prompt library |
| `safety` | safety gate: hard rules, model screen, LLM review, policy document |
| `workspace`, `lab` | confined workspaces, lab registry, Docker sandbox, built-in tools |
| `genome`, `runtime`, `memory` | agent genomes, graph executor and runner, agent memory |
| `wiring` | derives memory backend, workspace owner and lab permissions from the active genome |

Each package has its own `README.md`.
