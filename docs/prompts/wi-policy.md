# Program WI Policy

Invariant policy for every work item in this project's programs. This doc is
indexed (kind: prompt) and linked to WIs at creation so the context service
delivers it within the doc budget — NEVER paste these sections into WI bodies.

## Landing discipline
- Commit durable work to the `wi/<id>` branch BEFORE running final full-suite
  verification. Never end a session with uncommitted work.
- Verify with the project's pinned `verify_command` (see `.shipyard.yaml`).
- On migration-number collision after rebase: renumber your migration to the
  next free slot, update references, rerun verification.

## Session budget
- If a session exceeds its budget or stalls: commit WIP, write a handoff note
  (what landed with SHAs, what remains, branch/rebase state, next concrete
  step), end `progressed`.

## Definition of done
- Acceptance criteria in the WI body each have evidence.
- Tests pass via `verify_command`; no unrelated files touched; no secrets in
  diffs; review policy of the WI respected.

<!-- Extend with program-specific idempotency / UI-state / observability
     policy at planning time. -->
