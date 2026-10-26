# common

Shared building blocks used by every module.

| Class | Purpose |
|---|---|
| `Ids` | Time-sortable prefixed ids (`agt_…`, `gen_…`, `evt_…`). |
| `Actor` | Who performed an action (`USER`, `MASTER`, `AGENT`, `REFEREE`, `SYSTEM`); stored as `kind:id`. |
| `ErrorCode` / `MobiusException` | Error taxonomy with HTTP mapping and a `retryable` flag used by `Backoff`. |
| `Json` | Shared canonical Jackson mapper (sorted keys) + SHA-256 hashing. Canonical output keeps prompts and request bodies byte-stable for LLM prefix caching. |
| `Backoff` | Exponential backoff with jitter; retries only retryable `MobiusException`s. |
| `MobiusProperties` | Typed `mobius.*` configuration. |
| `audit.RevisionLog` | Append-only change history (`entity_revision`) for every audited entity. |

Conventions: audited tables carry the columns listed in `db/migration/V1__common.sql`; deletes are soft.
Thread safety: all classes are stateless or immutable except `RevisionLog` (stateless service over JDBC).
