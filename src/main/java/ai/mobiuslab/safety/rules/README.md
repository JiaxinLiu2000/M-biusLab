# ai.mobiuslab.safety.rules

Deterministic, model-free safety rules.

- `RuleSet` — command/secret regex rules (with ids and applicable action kinds), protected path globs, allowed hosts.
- `RuleSetLoader` — loads `classpath:safety/default-rules.yml`; extracts the policy's ` ```yaml safety-rules ` override.
- `HardRuleEngine` — evaluates a proposal; returns DENY/ESCALATE with rule ids, or "passed".

Rule ids: `shell.*`, `secret.*`, `path.escape.dotdot|outside-home|symlink`, `path.protected`, `path.missing`,
`network.unauthorized`, `frontend.no-screenshot`, `criteria.change`, `agent.delete.user-required`.
Thread-safe (compiled patterns cached in concurrent maps). No Spring dependencies.
