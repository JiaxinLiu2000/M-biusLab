# Möbius Lab — instructions for coding agents

Read before any work:
1. `README.md` — what exists and what is planned.
2. `PROGRESS.md` — current status of every step (Chinese); update it when you finish a step.

Rules:
- Code, comments, package READMEs: English. Progress notes: Chinese.
- Every package has a `README.md`; every class / public method has a one-line English comment. Keep them in sync with code.
- Cross-module contracts (interfaces/records in `llm`, `prompt`, `safety`, `genome`, `memory`, `workspace`, `lab`, `runtime`, `monitor`) are shared; do not change them without recording it in `PROGRESS.md`.
- Mutable tables carry the audit columns from `V1__common.sql`, every change goes through `RevisionLog`, deletes are soft.
- Every component reports through `monitor.MonitorBus`.
- JSON from LLMs only via `LlmGateway.chatStructured` (schema validation + 3 retries). Prompts only from `PromptLibrary`.
- Agent-written code runs only inside `lab.DockerSandbox`, after `SafetyGate` review.
- Commit message format: `v0.0.X 🔬<summary>`; bump the patch for small changes, the minor for large ones. The version lives only in `pom.xml` (`application.yml` reads `@project.version@`); bump it in the same commit.
- Never commit secrets (`.env` is ignored). Keys: `CRUSOE_API_KEY`, `TYPESAFE_API_KEY`.

Local infra: `docker compose up -d` (MySQL on 3307, Qdrant on 6333). Build: `mvn -q compile`; unit tests: `mvn test`; integration tests: `mvn -Pintegration test`.
