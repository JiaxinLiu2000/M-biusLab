# ai.mobiuslab.memory

Private / group / public agent memory: Obsidian vault (bodies) + MySQL `memory_note` (metadata, V10) +
Qdrant `mobius_memory` (vectors). Append-only: corrections invalidate, never overwrite.

## Main classes
- `DefaultMemoryService` — `MemoryService` bean. Every read/write checks `MemoryAccessPolicy` (FORBIDDEN otherwise),
  emits MEMORY_READ / MEMORY_WRITE (SourceKind.MEMORY) inside a span, dedups by (owner, scope key, content hash),
  caches `get` in Caffeine, routes agents to custom backends via optional `memoryBackendResolver`
  (`Function<String,String>` agentId → backend id), `registerBackend` requires `SafetyGate` (GENOME_CHANGE, `memory-backend:<id>`).
- `ObsidianQdrantBackend` ("obsidian") — vault file + DB row + vector; MySQL keyword fallback when Qdrant is degraded.
- `EpisodicLogBackend` ("episodic-log") — per-agent JSONL timeline, recency-weighted keyword search.
- `MemoryCompactor` — merges similar notes into a SUMMARY note, invalidates originals (`setSummarizer` for an LLM).
- `MemoryReindexer` — ensures the Qdrant collection on startup; `@Scheduled` retry of `index_pending` rows.
- `HashingEmbedder` (`Embedder`) — deterministic 384-dim hashed unigrams + char trigrams.
- `vault.ObsidianVault` — `public/`, `groups/<g>/`, `agents/<id>/`, `<slug>-<id>.md`, YAML frontmatter, `## Links` wikilinks.
- `index.QdrantIndex` — REST client; filters on `scope_key` (`PUBLIC`, `PRIVATE:<owner>`, `GROUP:<g>`) + `invalidated`.
- `store.JdbcMemoryNoteStore` — hand-written SQL, RevisionLog on insert/invalidate.

## Dependencies
common (Backoff, Json, RevisionLog, MobiusProperties), monitor, safety (lazy `ObjectProvider<SafetyGate>`),
genome (`Access`), a `MemoryAccessPolicy` bean from the genome/runtime module.

## Threading
All beans are thread-safe: per-note striped locks for vault writes (atomic temp + move), per-agent locks for
JSONL appends, unique DB constraint for concurrent duplicate writes, volatile degraded flag for Qdrant.
No lock is held during remote calls.
