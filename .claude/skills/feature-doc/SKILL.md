---
name: feature-doc
description: >-
  Write a post-implementation feature document (aka as-built doc) under
  docs/features/ that explains what was built and why. Use when the user asks
  to document a completed feature, write a design writeup for shipped work,
  create an as-built doc, or add/update a feature doc in docs/features/.
  NOT for forward-looking plans, PRDs, release notes, or roadmap specs.
---

# Writing a feature document

Post-implementation docs for this repo, living in `docs/features/<slug>.md`.
Audience: a future maintainer asking "why does this code look like this?"

- **Do** write one after a feature ships (or after a meaningful stage).
- **Don't** use a feature doc as a plan — plans go elsewhere.
- **Don't** slip into marketing / release-notes tone.

## File & naming

- Path: `docs/features/<kebab-slug>.md`. Slug reflects scope, not a stage.
- One feature, one doc. Multi-stage features append new stage sections.
- Link the doc from the PR description.

## Required structure

Keep it to **1–5 pages / 500–2500 words**. Skip n/a sections with `_n/a_`
rather than deleting, so the shape stays recognisable.

1. `# Feature: <Title>`
2. `## Goal` — 1 paragraph. What did this ship, in plain language?
3. `## Context` — Why did it exist? Past tense.
4. `## Goals / non-goals` — Bullets. Non-goals pin scope permanently.
5. `## What was built` — Architecture-level overview. Prefer a table /
   diagram over prose. Link to key files, don't paste code.
6. `## Key decisions` — 3–7 decisions that mattered. Each as a
   `### <Decision: X over Y>` sub-section: 1-line pick, real reason,
   alternatives considered (see below).
7. `## Trade-offs accepted` — What we knowingly gave up.
8. `## Known limitations / follow-ups` — What's deferred or fragile.
9. `## Operational notes` — Flags, rollout, metrics. Skip if n/a.
10. `## References` — PRs, related plan file, external links.

### Multi-stage features

Keep one doc, append stage sections:

```
## Stage 1 — <name>
...body, with its own decisions / trade-offs / follow-ups...

## Stage 2 — <name>
...
```

Update the top-of-doc "covers Stages 1–N" sentence each time. Prior
stages are append-only history — don't rewrite them.

## Documenting alternatives

Per alternative, use:

```
- **<Name>.** <1-line description.>
  Pros: <what it would have bought us>.
  Cons: <what it would have cost us>.
  Rejected because: <the actual reason, boring ones included>.
```

- 2–4 alternatives is the sweet spot. More is usually padding.
- Record boring reasons honestly: "we already had library X wired" is
  valid.
- Name the sacrifice explicitly when one exists: "traded X to get Y".

## Tone

- **Past tense, active voice.** "We chose X because Y."
- **Terse and factual.** Cut marketing phrasing.
- **Engineer-to-engineer.** Not user-benefit framing.
- **Link, don't paste.** Schemas, code, config already live in source.
- **Name honestly.** If it's a hack, call it a hack.

## Pitfalls

1. **Staleness by design** — duplicating code/config. Link instead.
2. **Post-hoc rationalisation** — record the actual reason, deadlines
   and constraints included.
3. **PRD drift** — user-benefit framing. Audience is a future maintainer.
4. **Kitchen-sink** — cut anything not needed to explain *why the system
   looks like this*.
5. **Missing non-goals** — include them even when short.
6. **Orphaned** — require bidirectional links (PR ↔ doc).

## Workflow for the agent

1. Identify scope — one feature, or a stage? If ambiguous, ask.
2. Read the actual implementation and related plan file before drafting.
3. Pick a slug; update an existing `docs/features/<slug>.md` when the
   scope overlaps rather than creating a sibling.
4. Draft all required sections; mark `_n/a_` where genuinely not applicable.
5. For each key decision, capture the *real* reasoning from chat/commit
   history, not a sanitised version.
6. Stay under 2500 words. If you're over, you're padding.
7. Sanity check: at least one non-goal, one trade-off, one follow-up.
   If any of those three is empty, re-examine.

## Example docs in this repo

- [`docs/features/perry-recall-integration.md`](../../../docs/features/perry-recall-integration.md) — multi-stage, good append example.
- [`docs/features/memory-briefing.md`](../../../docs/features/memory-briefing.md) — single-stage, canonical template.
