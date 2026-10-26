# ai.mobiuslab.llm

Single gateway for every model call: Crusoe (chat completions) and Jev / TypeSafe System One (typed decisions).

## Main classes
- `LlmGateway` (contract) → `DefaultLlmGateway`: model resolution (`ModelRegistry` role `worker` by default), `Backoff`
  retries of retryable errors, one Resilience4j circuit breaker per provider (only transient failures count),
  Caffeine idempotent result cache for `temperature == 0` (10 min, 2000 entries, key = `Json.sha256` of request),
  empty-content/`finish_reason=length` retry with doubled `max_tokens`, monitor span (`SourceKind.LLM`,
  sourceId = callerModule) plus `LLM_CALL` / `DECISION` event with model, tokens, cache hit, cost, latency.
- `chatStructured`: sends `response_format: json_schema (strict)`; if a model answers 400 mentioning
  `response_format`/`json_schema` it is remembered and the schema is embedded in a final user message instead.
  Replies are fence-stripped, parsed, validated (networknt, 2020-12); failures append the reply + a user message
  listing errors; 1 + `mobius.llm.structured-retries` (3) attempts, then `LLM_BAD_OUTPUT`.
- `CrusoeClient`: `POST {baseUrl}/chat/completions`, per-model `Semaphore` cap, pauses a model until the next
  minute when `x-ratelimit-remaining-requests/-tokens` hits 0. `JevClient`: `POST {base}/v1/systemone`.
- `LlmHttpException`: status + body; 429/5xx/529 retryable, 400/401/403/404/413/415/422 not.
- `ModelRegistry`: `classpath:models.yml` (provider, USD per 1M input/cachedInput/output, context, roles);
  `cost = ((prompt-cached)*in + cached*cachedIn + completion*out) / 1e6`; unknown model → WARN once, cost 0.
- `UsageMeter implements UsageReport`: LongAdder aggregates by model/module/agent (incl. calls in the last minute,
  failures), budget WARN once at `credit * warnRatio`; async batch insert into `llm_call` via `LlmCallRepository`.
- `LlmProperties` (`mobius.llm.*`): defaults in code.

## Dependencies
`common` (Json, Backoff, MobiusProperties), `monitor` (MonitorBus). No dependency on safety or prompt.

## Threading
All beans are thread-safe; calls block (virtual-thread friendly). No locks are held during remote calls.
`UsageMeter` owns one daemon writer thread (`llm-usage-writer`); its queue is bounded and drops (counted) when full.
