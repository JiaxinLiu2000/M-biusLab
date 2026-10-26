# ai.mobiuslab.safety

Safety officer: every risky agent action passes `SafetyGate` before it happens.

## Pipeline (`DefaultSafetyGate`)
1. **Hard rules** (`rules.HardRuleEngine`, no model calls): destructive shell, path escape, protected paths,
   secrets (long-token check ignores platform ids like `prj_<ulid>-slug`), unauthorized network, FRONTEND_CHANGE without `attachments.screenshotPath` → DENY;
   CRITERIA_CHANGE and (by setting) AGENT_DELETE → ESCALATE. Decisive hits short-circuit.
2. **Jev screen** (`review.JevSafetyScreen`): noul "harmful/oversteps", 5-level risk score, category.
   Low risk + low harm + confident → ALLOW without LLM.
3. **LLM review** (`review.LlmSafetyReviewer`, `deepseek-ai/Deepseek-V4-Flash`, fixed prompts in
   `resources/safety/prompts`, never PromptLibrary). Jev/LLM failure (any RuntimeException) → fail closed (DENY at high risk, else ESCALATE).

Verdicts are idempotent by proposal id (`VerdictStore` / `JdbcVerdictStore`, table `safety_verdict`) and
reported as `SAFETY_VERDICT` (plus `USER_ESCALATION`) monitor events. `reviewAll` runs on virtual threads,
bounded by `mobius.safety.max-concurrent-reviews`, preserving order.

## Policy (`SafetyPolicyService` / `JdbcSafetyPolicyService`)
`resources/safety/default-policy.md` is copied on first start to `${mobius.home}/safety/SAFETY_POLICY.md`.
Every version is stored in `safety_policy_revision`; edits to the file on disk are hot-reloaded. A fenced
` ```yaml safety-rules ` block in the policy overrides lists from `resources/safety/default-rules.yml`.
Settings (`SafetySettings`) live in `safety_setting`. Only `Actor.Kind.USER` may change policy/settings.

## Chat (`SafetyChatService`)
User-only conversation (`safety_chat_message`), no shared memory. Policy changes are only *proposed*;
`applyProposal` (REST `POST /api/safety/chat/{id}/apply-proposal`) applies them after user confirmation.

## Dependencies / threading
Depends on `llm.LlmGateway`, `monitor.MonitorBus`, `common` (RevisionLog, Json). Never on PromptLibrary.
All beans are thread-safe: caches are concurrent, the policy lock is never held during model calls.

Config: `mobius.safety.*` (`SafetyProperties`), defaults in code.
