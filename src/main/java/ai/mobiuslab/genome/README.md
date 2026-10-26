# genome

Versioned, immutable agent definitions (genomes), their lineage, and the reusable module catalog.

| Class | Purpose |
|---|---|
| `Genome`, `NodeGene`, `EdgeGene`, `PermissionSet`, ... | Contract records (unchanged). |
| `GenomeService` / `JdbcGenomeService` | Save (nodes with a `promptKey` but no `promptVersion` are first pinned to `PromptLibrary.latest(key)`, unknown keys stay unpinned with a WARN; dedupe by `(agent_id, content_hash)` of the pinned genome), get, active, activate, rollback (parent re-activated, current `ROLLED_BACK`), setState, recordScore (`score_summary` JSON `{objective: {count, avg, last}}`, optimistic `row_version`), lineage, history. Publishes `GenomeActivatedEvent`. |
| `ModuleCatalog` / `JdbcModuleCatalog`, `ModuleDef` | Reusable node modules keyed `(module_id, version)`; `version <= 0` on register = next version; search by kind/text ordered by reuse. |
| `GenomeMemoryAccessPolicy` | `MemoryAccessPolicy` impl: PRIVATE always READ_WRITE for the owner; MASTER defaults PUBLIC READ_WRITE; group members default READ; explicit `PermissionSet.memory` wins. Caffeine cache invalidated on `GenomeActivatedEvent`. |
| `GenomeTemplates` | Static builders (`singleLlmAgent`, `graph`, `node`, `defaultPermissions`). |

Tables (`V7__genome.sql`): `agent`, `genome`, `module_def`. The `genome.state` column is authoritative; `body` holds the full
genome JSON. `agent.active_genome_id` is kept in sync on activate/rollback.

Dependencies: `JdbcClient`, `TransactionTemplate`, `RevisionLog`, `MonitorBus` (STATE_CHANGE events).
Threading: stateless beans plus thread-safe Caffeine caches; activation runs in one DB transaction.
