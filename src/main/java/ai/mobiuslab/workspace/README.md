# workspace

Per-agent private workspaces and one shared workspace under `${mobius.home}/workspaces/{agents/<id>,shared}`.

| Class | Purpose |
|---|---|
| `WorkspaceManager` | Contract: roots, confined `resolve`, `withSharedRead`/`withSharedWrite`, `list`. |
| `FileSystemWorkspaceManager` | Implementation. Agent ids must match `[A-Za-z0-9_.-]+`; roots are created lazily. `resolve` rejects absolute paths, `..` escapes and symlinks leading outside (real path of the nearest existing ancestor) with `FORBIDDEN`. Optional bean `workspaceOwnerResolver` (`Function<String,String>`) maps an agent to the owner of the workspace it shares (WorkspaceSpec SHARED_WITH); default identity. |

Locking (shared area): one fair `ReentrantReadWriteLock` per normalized path, kept in a ref-counted
`ConcurrentHashMap` (entries are removed when unused). A writer read-locks every parent and write-locks
the path; a reader read-locks the path and its parents. Locks are acquired in sorted path order (parents
first) to avoid deadlock, each with the remaining timeout; failure → `MobiusException(TIMEOUT)`. Writers
also hold a `FileChannel` lock on `shared/.locks/<hash>.lock` for cross-process safety (hidden from `list`).

Monitoring: shared writes emit `DATA_FLOW` and lock waits > 1 s emit `LOG` events with `SourceKind.WORKSPACE`.

Dependencies: `common` (MobiusProperties, MobiusException), `monitor`. Thread-safe.
