# Research: Completed-Feature Docs (As-Built / Post-Hoc Design Docs)

*Researched: 2026-04-18*

## 1. Canonical naming — what the industry actually calls this

There is **no single canonical term** for the post-implementation genre. The related terms overlap but are genuinely distinct:

| Term                    | Timing                    | Scope            | Audience              | Permanent?                       |
| ----------------------- | ------------------------- | ---------------- | --------------------- | -------------------------------- |
| **RFC**                 | Before decision           | Proposal + feedback | Team, stakeholders | Archived after decision          |
| **Design doc** (Google) | Before / during build     | Whole feature    | Reviewers, maintainers | Living, updated through build   |
| **ADR** (Nygard)        | Immediately after a decision | One decision  | Future developers     | Immutable, append-only           |
| **Tech spec**           | Implementation planning   | Interface detail | Implementers          | Often rewritten                  |

The post-hoc feature doc genre doesn't have a clean industry name. In practice it is **a Google-style design doc whose status field is `Implemented`** (Malte Ubl explicitly treats design docs as living through the build, ending in an "as-built" state), or equivalently **a "mega-ADR"** — a cluster of decisions framed around a completed feature. Common informal labels: *feature doc*, *implementation notes*, *as-built doc*, *engineering writeup*.

A feature doc is not a looser ADR — ADRs are narrow (one decision each). A feature doc is the *surrounding context + a bundle of ADR-like decisions + what shipped*.

## 2. Recommended structure

1. **Metadata** — title, author(s), status (`Implemented`), date, links to PRs / issues / commits.
2. **Summary** (≤3 paragraphs) — what was built, in plain language, as shipped.
3. **Context & problem** — why this work existed; constraints at the time (past tense).
4. **Goals / non-goals** — what it was and wasn't trying to do; pins scope permanently.
5. **What was built** — architecture overview, key components, data/API shape. Prefer a diagram over prose.
6. **Key decisions** — 3–7 decisions that actually mattered, each with its "why". Mini-ADR inline or linked.
7. **Alternatives considered** — per decision: options evaluated, trade-offs, why rejected.
8. **Trade-offs accepted** — what was knowingly sacrificed (perf, flexibility, cost, debt).
9. **Known limitations / follow-ups** — what's not done, deferred, or fragile. Link issues.
10. **Operational notes** — flags, rollout state, metrics, on-call implications (skip if n/a).
11. **References** — PRs, RFCs/design docs that preceded it, related ADRs, external reading.

**Length:** 1–5 pages (≈500–2500 words). Ubl's rule — *"as short as possible, as long as necessary"*. Beyond ~20 pages, rot is nearly guaranteed.

**Tone:** past-tense, active voice, terse, factual. *"We chose X because Y; Z was rejected because W."* No marketing. No hedging. No forward-looking roadmap language (that belongs in RFCs/specs).

## 3. Pitfalls

1. **Staleness by design** — if the doc duplicates code, API schemas, or config, it will drift. Link to the source of truth, don't copy it.
2. **Post-hoc rationalisation** — writing the decision history as if the team were prescient. Record the actual reason ("deadline", "existing library", "team familiarity"), including the ugly ones.
3. **PRD / release-notes drift** — authors slide into user-benefit framing or future roadmap. Keep it engineer-to-engineer and past-tense.
4. **Kitchen-sink syndrome** — every detail gets included; no one reads it. Cut anything not needed to understand *why the system looks like this*.
5. **Orphaned docs** — no link from code/PRs back to the doc, and vice versa. Require bidirectional links.
6. **No "non-goals"** — without explicit non-goals the doc invites scope-creep rewrites later.

## 4. "Why we chose X over Y" — conventions for alternatives

From Nygard, Ubl, and the MADR tradition:

- **One sub-section per real alternative** — not a grab-bag of strawmen. 2–4 is the sweet spot.
- **Fixed sub-structure** per alternative: *Description · Pros · Cons · Reason rejected.*
- **Name the criteria** used to decide (perf, complexity, ops cost, team familiarity, migration risk). For non-trivial calls, a weighted scoring matrix makes the decision defensible.
- **Explicit sacrifice statement** — "We traded away X to get Y." Don't imply it.
- **Record throwaway options** that were seriously considered and dropped early — these save future readers from relitigating.
- **Link, don't rehash** — if a full RFC exists, link it; don't re-summarise.

## 5. Best source templates / examples

1. **[Design Docs at Google — Malte Ubl](https://www.industrialempathy.com/posts/design-docs-at-google/)** — closest to a canonical template for the genre; explicitly treats docs as living through implementation and emphasises trade-offs + alternatives as the core.
2. **[Documenting Architecture Decisions — Michael Nygard (2011)](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions)** — the ADR origin. Structure (*Context → Decision → Status → Consequences*) is a drop-in for the "Key decisions" section of a feature doc.
3. **[joelparkerhenderson/architecture-decision-record](https://github.com/joelparkerhenderson/architecture-decision-record)** + **[adr/madr](https://github.com/adr/madr)** — the two most-used ADR template collections. MADR's "considered options / pros & cons / decision outcome" structure is directly reusable.

Runner-ups:

- Kubernetes KEP process ([kubernetes/enhancements](https://github.com/kubernetes/enhancements)) — heavier, graduated template.
- Will Larson's [Writing engineering strategy](https://staffeng.com/guides/engineering-strategy/) — tone and "prefer good over perfect".
- Will Larson's [Making engineering strategies more readable](https://lethain.com/readable-engineering-strategy-documents/) — structure-for-readers vs structure-for-writers.

## 6. Caveats

- No industry-standard name exists specifically for the post-implementation variant — we should coin one internally ("feature doc") and state the convention explicitly so contributors don't confuse it with a PRD or RFC.
- Whether to split key decisions into separate ADR files vs. inline them is a team preference. Default: *inline for ≤5 decisions, separate ADR files beyond that*.
