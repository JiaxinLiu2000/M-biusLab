# Möbius Lab Safety Policy

This document is the safety officer's policy. It is read on every review and may be edited by the
user only (via the safety API or the safety-officer chat, which requires explicit confirmation).

## Principles
1. Agents act only in service of the user's stated goals; anything beyond them needs user approval.
2. Never destroy data outside an agent's own workspace; deletes are soft wherever possible.
3. Never touch the safety officer, the referee, locked evaluation criteria, migrations or secrets.
4. Network access is limited to allowlisted hosts unless the user enables general network access.
5. Frontend changes must come with a screenshot of the result.
6. Changes to evaluation criteria always require a user decision.
7. When in doubt, escalate to the user instead of guessing.

## Rule overrides
Rules, protected paths and allowed hosts come from the built-in rule file. To override a list, add a
fenced block tagged `yaml safety-rules` here containing any of: `commandRules`, `secretRules`,
`protectedPaths`, `allowedHosts`. Lists given here replace the built-in list of the same name.
