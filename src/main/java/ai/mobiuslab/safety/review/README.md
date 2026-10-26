# ai.mobiuslab.safety.review

Model-backed reviewers used by `DefaultSafetyGate`.

- `JevSafetyScreen` — one Jev `decide` call: noul "harmful/oversteps", risk score (none..critical), category.
- `LlmSafetyReviewer` — `LlmGateway.chatStructured` with schema `{decision, risk, reasons}` and the live policy text.
- `SafetyPrompts` — fixed prompts from `classpath:safety/prompts/*.md` (safety is not evolvable; no PromptLibrary).

Depends on `llm.LlmGateway` only. Stateless and thread-safe; failures propagate as `MobiusException` so the
gate can fail closed.
