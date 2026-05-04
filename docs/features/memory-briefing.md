# Feature: Memory briefing in the system prompt

## Goal

Give Perry a compact, per-turn summary of what's currently in long-term
memory, injected into the system prompt, so Perry knows what exists
before deciding to search. MemPalace-style: ~170 tokens of "what do I
know?" on every turn.

## Context

Before this change, the system prompt was `soul.md + instructions.md`
only. Perry had no awareness of what shelves existed or what had been
captured — every question started from a blank slate. Deciding whether
to call `searchMemory` / `recall` was a pure guess.

We already had the machinery for this. `LayeredContext.wakeUp()` built a
three-layer L0/L1/L2 context block (identity, recent pages, shelf
summaries), and `SummaryStore` held compressed per-page summaries from
the mining pipeline. But `wakeUp()` was only called in tests — production
paths never fed it into the LLM. MemPalace's "170-token startup brief"
is exactly that pattern, and was what prompted the comparison.

## Goals / non-goals

**Goals**

- Prepend a compact "what's in memory" block to the system prompt on
  every turn.
- Keep it under ~150 tokens so per-turn cost is negligible.
- Surface *shelf inventory* (what categories exist, and how much is
  in each), not just recent items — it's more useful to the model than
  a scrolling tail.
- Regenerate per-turn so the brief reflects `remember` / `forget` without
  a restart.
- Expose the brief via the CLI so it can be inspected without Discord.

**Non-goals**

- Per-user scoping of the brief (all captures are visible today; Stage 4
  of the recall roadmap is where this is addressed).
- Fancier summarisation — no LLM calls, no compression scheme beyond
  what `SummaryCompressor` already does.
- Caching / memoisation. Regeneration is O(N pages) and measured in
  single-digit ms for our corpus.
- Displacing the existing `wakeUp()` API. It remains for tests and any
  future CLI-wake scenarios.

## What was built

| Component                                            | Role                                                           |
| ---------------------------------------------------- | -------------------------------------------------------------- |
| `LayeredContext.briefing()`                          | New method: shelf inventory + recent highlights, no L0         |
| `AiConfig#buildSystemMessage`                        | Composes `soul + instructions + briefing()` per turn           |
| `RecallCommand --briefing`                           | CLI flag that prints the briefing + store stats                |
| `brainjar` wrapper                                   | Routes `--briefing` to the CLI Spring profile                  |

Output format:

```text
## Memory briefing
Shelves: docs (93), memorySampleSet (9), user:…:tech (2), ... [, +N more]
Recent:
- [shelf] key sentence from summary, or snippet of page content
- ...
```

Token budget: `BRIEFING_RECENT_MAX_CHARS = 400`, `BRIEFING_SNIPPET_LENGTH = 100`,
plus the inventory line. Measured ~524 chars / ~131 tokens on the real
store (108 pages across 5 shelves).

Key files:

- [`src/main/java/brainjar/recall/search/LayeredContext.java`](../../src/main/java/brainjar/recall/search/LayeredContext.java)
- [`src/main/java/brainjar/discord/ai/AiConfig.java`](../../src/main/java/brainjar/discord/ai/AiConfig.java)
- [`src/main/java/brainjar/recall/RecallCommand.java`](../../src/main/java/brainjar/recall/RecallCommand.java)
- Tests: [`LayeredContextTest.java`](../../src/test/java/brainjar/recall/search/LayeredContextTest.java) (briefing cases)

## Key decisions

### Briefing separate from `wakeUp()` rather than replacing it

`wakeUp()` includes L0 (identity from `soul.md`). The system prompt
already carries `soul.md` directly; calling `wakeUp()` would duplicate
identity into the prompt. Instead we kept `wakeUp()` intact and added a
slim `briefing()` that skips L0 and replaces the chunky L1 with a shelf
inventory + summary-driven highlights.

Alternatives:

- **Parameterise `wakeUp(includeIdentity=false)`.** Would avoid the new
  method but pollute the signature for a cross-cutting concern and
  force callers to know the flag. Rejected on interface-hygiene grounds.
- **Strip L0 from `wakeUp()` output at the call site.** Fragile — the
  "## Identity" header could change. Rejected.

### Shelf inventory over recency-only list

The first draft mirrored `wakeUp`'s L1: a list of recent page snippets.
That gave the model a scrolling tail but no sense of *what categories
existed*. Switching to `shelf (count), …` as the primary line, with a
smaller "Recent" block underneath, lets the model see "there's a
`preferences` shelf I could search" without wading through snippets.

Alternatives:

- **Shelf inventory only.** Too coarse. The model loses any signal
  about what was most recently talked about.
- **Recent only.** Original draft; rejected as above — no sense of the
  larger landscape.
- **Entity-frequency top-N across summaries.** Closer to a true concept
  map but not obviously more useful than shelf counts, and harder to
  bound in tokens. Deferred.

### Per-turn regeneration over caching

`systemMessageProvider` is invoked per turn by LangChain4j, so the
briefing refreshes every message. Cost is a single `pageStore.recent()`
scan plus a `groupBy` — cheap at our scale.

Alternatives:

- **Cache with TTL.** Saves a few ms per turn, adds invalidation logic
  (`remember` / `forget` would need to bust it). Rejected: no measurable
  benefit today, and staleness would confuse debugging.
- **Session-scoped cache (regenerate on first turn, keep for session).**
  Same problem — `remember` mid-session would produce a stale brief.

### Hard token budget via char caps, not token counting

We cap chars (`BRIEFING_RECENT_MAX_CHARS = 400`, snippet length = 100)
and divide by 4 for the token estimate shown in the CLI. Cheap, no new
dependency on a tokenizer, error bar is small for English.

Alternatives:

- **Use a real tokenizer (tiktoken-equivalent in Java).** More accurate,
  adds a dependency, and the inaccuracy of the char estimate costs us
  maybe 10-20 tokens of headroom, which we have.

## Trade-offs accepted

- Brief is **not user-scoped** — on a multi-user deployment it would
  leak one user's captures into another user's system prompt. Today we
  are single-user; Stage 4 of the recall roadmap is where this is
  closed.
- Shelf inventory is truncated at 8 shelves with a `+N more` marker.
  The 9th-most-populated shelf is invisible until Perry searches for
  it. Acceptable until the store has many more shelves than that.
- "Recent" uses `pageStore.recent()` which is ordered by book
  `lastModified`, which tracks mine-time rather than capture-time for
  daily capture books that append throughout a day. Not a bug, but
  worth noting.

## Known limitations / follow-ups

- Per-user briefing (Stage 4 prerequisite).
- `recent()` order vs capture-order mismatch noted above.
- No visibility into *which* summaries are missing — pages without a
  `Summary` entry fall back to raw snippets silently. If the summary
  pipeline regresses, the briefing will grow less compact without an
  obvious signal.

## References

- Prior doc: [`perry-recall-integration.md`](perry-recall-integration.md) —
  Stages 1–3 of the recall integration.
- MemPalace research: [`../research/mempalace.md`](../research/mempalace.md).
- Feature-doc conventions used here: [`../../.cursor/skills/feature-doc/SKILL.md`](../../.cursor/skills/feature-doc/SKILL.md).
