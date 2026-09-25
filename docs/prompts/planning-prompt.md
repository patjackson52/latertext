# Planning Prompt Template — large program → epics → WIs

Prompt skeleton for the planning session that turns an accepted spec set into a
Shipyard program. Incorporates the 2026-07-24 audit findings. Pair with
`docs/plans/2026-07-24-project-onboarding-playbook.md` (phases 2–3).

Replace `{...}` placeholders. Run the planning session against the pinned spec
commit, never a moving branch.

---

## Prompt

You are planning a Shipyard program for project `{P-n}` (`{repo path}`) from the
accepted spec set at commit `{spec commit sha}` ({spec PR link}). Produce the
machine artifacts listed under OUTPUTS. Do not begin implementation.

### Inputs

- Spec set: `{paths}`
- Repo state: `{main sha}`; migration numbering head: `{highest applied migration}`
- Incident/waste history to plan around: finish-line death (sessions must
  commit before final verification), dirty-worktree dispatch storms,
  migration-number collisions between parallel lanes, oversized UI WIs,
  review-parked branch redispatch.
- Calibration data (last program, 90 WIs): backend WI ≈ $5–28 single session;
  gate WI ≈ $2–6; UI page WI ≈ $20–30 when split correctly; any WI projected
  above one session of its tier must be split.

### Decomposition rules

1. Milestones → epics → WIs. Epic bodies are orchestration-only (member
   manifest, entry/exit gates); no implementation detail.
2. One WI = one session of its assigned tier. UI work: one page/surface per WI;
   a11y/mobile/state-matrix as explicit sibling WI, never implied scope.
3. Tier = max(reasoning difficulty, context volume). High-volume/low-difficulty
   work (large UI surfaces, wide mechanical refactors) tiers UP or splits.
   Never assign an effort level a target harness does not support.
4. Reserve migration numbers per WI at planning time, in dependency order.
   State the renumber rule in each migration-bearing WI.
5. Dependencies are data: emit native dep edges (`/wi/{id}/deps`), generated
   from the manifest. The manifest is the generator, not the runtime authority.

### WI body contract (≤ 4000 chars)

Required sections — nothing else:

1. **Objective** — 2–4 sentences, WI-specific.
2. **Context packet** — execution focus; prerequisite WIs whose deliverables to
   consume (deliverables, not worktrees); pinned spec anchors with line numbers;
   code seams; test seams; 1–3 bounded `rg` escape hatches.
3. **Scope / out of scope** — bullets, concrete.
4. **Data model / contract deltas** — exact tables, routes, CLI verbs; reserved
   migration number.
5. **Acceptance criteria** — falsifiable bullets; each becomes an
   `evidence require` row at creation.
6. **Landing runbook** — exact verify command (from project config); commit
   discipline: durable commit to `wi/{id}` BEFORE launching final full-suite
   verification; landing sequence incl. rebase + migration-renumber procedure;
   per-session budget `{$N}` with bail-out ("if exceeding, commit WIP, write
   handoff note, end `progressed`").
7. **Handoff note format** (for any non-completed ending): what landed (shas),
   what remains, branch/rebase state, next concrete step.
8. **Tier + rationale** — one line, volume-aware (rule 3).

Invariant program policy (idempotency, UI states, observability, DoD, autonomy)
lives in ONE indexed policy doc linked to every WI — never pasted into bodies.
Bodies reference it by name only.

### OUTPUTS

1. `docs/plans/{date}-{program}-roadmap-manifest.mjs` — milestones/epics/WIs/
   blocker edges/tiers as data, checksummed, pinned to `{spec commit sha}`.
2. `docs/plans/{date}-{program}-context-packets.mjs` — per-WI packet inputs
   (spec anchors, seams). If the dispatch-time packet generator
   (`spike/context_service`) is wired, emit provider queries instead of static
   text.
3. `docs/plans/{date}-{program}-wi-policy.md` — the single invariant policy doc
   (index + `wi link` to all WIs at creation).
4. Creation script that, per WI: creates the record, writes native dep edges,
   creates `evidence require` rows from §5, links the policy doc and each
   pinned spec doc (`wi link`), pins the tier BEFORE any status move to `next`.
5. Readback verification: created-ids JSON mapping plan keys → WI ids; diff
   against manifest; `shipyard context <WI>` spot-check on 3 WIs confirming
   DOCS section populated and doc budget engaged.

### Pruning gate (answer before emitting outputs)

For each epic past the core milestone: which observed incident or concrete user
story does it serve at current deployment scale? Anything justified only by
"an org would need this" is cut or parked `later` with the justification
recorded in the manifest.
