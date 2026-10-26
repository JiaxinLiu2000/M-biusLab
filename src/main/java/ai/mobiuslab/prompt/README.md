# ai.mobiuslab.prompt

Versioned, modular prompt library stored in `prompt_template` (V4).

## Main classes
- `PromptLibrary` (contract) → `DbPromptLibrary`: Caffeine-cached reads, `render()` (counts invocations),
  `propose()` → `SafetyGate.require(PROMPT_CHANGE, target = key, content = segments JSON)` then, in one
  transaction, supersedes the current latest (optimistic `row_version` check) and inserts `max + 1`; RevisionLog
  entry per version. Concurrent proposals → `CONFLICT`.
- `PromptRenderer`: stable sort by `volatility`, `{{var}}` substitution, adjacent same-role segments merged with
  `"\n\n"`; missing variable → `VALIDATION`. Same stable segments ⇒ byte-identical prefix (prefix-cache friendly).
- `InvocationCounter`: LongAdder per (key, version); drained every 5 s by `@Scheduled flushInvocations()` and on
  shutdown with one `batchUpdate`. Counter flushes are metrics, not content changes, so they are not RevisionLog'd.
- `PromptSeedLoader`: on startup imports every `classpath*:prompts/*.yml` prompt as v1 if the key does not exist.
  File format: `prompts: [{key, ownerAgentId?, segments: [{role: SYSTEM|USER|ASSISTANT, volatility, text}]}]`.
  Other modules add their own `prompts/<module>.yml`.

## Dependencies
`common` (RevisionLog, Json), `monitor`, `safety` (lazily via `ObjectProvider<SafetyGate>`), `llm.ChatMessage`.

## Threading
Thread-safe; counters are lock-free; DB writes happen outside any lock.
