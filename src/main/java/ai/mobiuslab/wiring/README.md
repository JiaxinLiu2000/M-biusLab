# wiring

Cross-module glue that derives runtime policy from each agent's **active genome**. Modules expose optional
named beans; this package provides them so the genome stays the single source of truth.

| Bean / listener | Consumer | Behaviour |
|---|---|---|
| `memoryBackendResolver` (`Function<String,String>`) | `memory.DefaultMemoryService` | agent id → `MemorySpec.backend` (default `obsidian`), enabling different memory systems per agent. |
| `workspaceOwnerResolver` (`Function<String,String>`) | `workspace.FileSystemWorkspaceManager` | agent id → owner of the workspace it uses (`WorkspaceSpec.SHARED_WITH`), else itself. |
| `labPermission` (`BiPredicate<String,String>`) | `lab.DefaultLabRegistry` | (agentId, toolId) allowed only if the active genome grants the tool in `permissions.labs` or `permissions.tools`; agents without an active genome are denied (fail closed). |
| `onGenomeActivated` (`@EventListener`) | this class | Invalidates the cached active genome whenever a genome is activated or rolled back. |

Active genomes are cached (Caffeine, 10 min) and invalidated on every `GenomeActivatedEvent`.
Dependencies are injected `@Lazy` to avoid bean cycles. Thread-safe.
