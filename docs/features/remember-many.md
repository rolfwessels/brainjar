# Feature: rememberMany batch writes + open shelf taxonomy

## Goal

Let Perry capture a multi-item list (e.g. "five movies and three TV
series to watch") in a single tool call, with each item routed to a
shelf of Perry's own choosing. Drop the closed shelf vocabulary in
favour of an open, free-form taxonomy with reuse hints rather than
hard rules.

## Context

Perry was dropping list-style captures. A representative incident:
the user gave four movies in one message; Perry stored *one* and
quietly forgot the other three. Two compounding causes:

1. **One-shot `remember(content, shelf)` only.** Perry had to issue
   N tool calls for an N-item list. Under chat-time constraints it
   shortcut to "store the most representative one and discuss the
   rest" — losing durable state.
2. **Closed shelf list in `instructions.md`.** The original taxonomy
   enumerated ~10 shelves (`profile`, `tech`, `notes`, …) with no
   `movies`, `series`, `books`, etc. When Perry saw an entire
   category that didn't fit, the path of least resistance was "skip,
   it's chatter" rather than "invent a shelf."

The combination meant lists were both expensive to write *and*
discouraged from being written at all.

## Goals / non-goals

**Goals**

- Single tool call to store many items across many shelves.
- Free-form shelf creation with reuse hints, not a fixed list.
- Update `instructions.md` so Perry actively prefers `rememberMany`
  for any list-style input.
- Preserve the existing daily-capture-book layout (one book per
  user-shelf-day, append pages into it) so storage shape is
  unchanged.

**Non-goals**

- Per-item dedupe at write time — the existing forget tools handle
  cleanup; pre-write dedupe would slow the common path.
- Auto-merging similar shelves (`movie` vs `movies`) — Perry is
  expected to normalise via the reuse hint in the prompt.
- Backfilling old single-item captures into batches.
- Returning per-item IDs from the batch call — the summary string
  is sufficient feedback for Perry to confirm.

## What was built

| Component | Role |
| --------- | ---- |
| [`MemoryItem`](../../src/main/java/brainjar/recall/MemoryItem.java) | Record `(shelf, content)` — one entry in a batch. |
| [`RecallTool#rememberMany`](../../src/main/java/brainjar/recall/RecallTool.java) | New `@Tool` accepting `List<MemoryItem>`; groups by shelf, writes each into that shelf's daily capture book, returns a per-shelf summary. |
| [`RecallTool#userDailyCaptureBook`](../../src/main/java/brainjar/recall/RecallTool.java) | Extracted helper used by both `remember` and `rememberMany` so they share book-allocation logic. |
| [`instructions.md`](../../src/main/resources/instructions.md) | Renamed "Suggested shelf taxonomy" → "Shelves — open taxonomy"; added `rememberMany` to the tool table; added a mixed-shelf classification example; mandated `rememberMany` for any list. |

Behaviour notes:

- Blank `content` entries are silently skipped (Perry occasionally
  passes empty strings when iterating a partial list).
- Empty input list returns a no-op message instead of throwing.
- Shelf names are normalised the same way as single-write
  `remember` (lower-kebab via `Shelf` constructor).
- Chunk indices continue the existing per-book sequence — a batch
  of three items appended to a book that already has 7 pages
  becomes pages 8, 9, 10.

## Key decisions

### `List<MemoryItem>` over `Map<String, List<String>>`

The original sketch had a shelf-keyed map. `List<MemoryItem>` keeps
the per-item shelf binding visible at the call site, matches how
LangChain4j renders tool schemas to the model (positional records
beat nested maps), and trivially supports the same shelf appearing
multiple times (`[(movies, A), (series, B), (movies, C)]`).

Alternatives:

- **`Map<String, List<String>>` keyed by shelf.** More compact when
  many items share a shelf, but loses ordering and forces Perry to
  pre-group — which is the same friction we were trying to remove.
- **Variadic `remember(String shelf, String... contents)` per shelf.**
  Still forces Perry to issue N calls for N shelves.

### Daily capture books, not "batch books"

Each item still lands in the existing per-shelf-per-day capture
book. We considered creating a single "batch" book per
`rememberMany` call to preserve provenance, but the existing daily
book is what `searchMemory` and `--latest` already understand;
introducing a new book shape would have rippled into the recall
pipeline.

Alternatives:

- **One book per batch.** Cleaner provenance ("these were saved
  together") but breaks the daily-capture invariant and creates
  many tiny books.
- **Stuff the entire batch into a single page.** Saves a few
  embeddings but defeats per-item retrieval.

### Open taxonomy with reuse hints, not a fixed list

The closed list was acting as a hidden gate ("if it doesn't fit
these 10, drop it"). Replacing it with "create what you need, but
reuse `<existing label>` if a close match exists" leans on the
fact that the memory briefing already injects the live shelf
inventory into the system prompt — Perry sees what already exists
and can re-use it.

Alternatives:

- **Keep the closed list, expand to ~30 entries.** Just delays the
  problem; any list we hand-pick will miss something.
- **Auto-cluster similar shelves at read time.** Real solution but
  out of scope; deferred.

## Trade-offs accepted

- Shelf sprawl is now possible. A careless Perry could create
  `movies-to-watch`, `movies_watch`, and `watch-movies` for the
  same concept. The reuse hint in `instructions.md` plus the
  briefing's shelf inventory mitigate, but don't prevent, this.
- `rememberMany` returns a summary string, not structured page IDs.
  Perry can't immediately reference a freshly-written item by ID
  without a follow-up `searchMemory`. Acceptable for the
  list-capture use case which is the motivating workflow.
- No transactional guarantee across the batch. If page N fails
  mid-loop, pages 1..N-1 are already persisted. The store is
  append-only with stable IDs so a retry is safe, but the partial
  state will be visible until then.

## Known limitations / follow-ups

- No fuzzy shelf-name suggestion at write time (see "Trade-offs").
- No per-item dedupe; identical content saved twice creates two
  pages with different IDs (different chunk indices).
- The new "open taxonomy" guidance is prose in `instructions.md`;
  a more enforceable mechanism (e.g. tool description that lists
  current shelves at call time) would close the loop.

## References

- Sibling: [`recall.md`](recall.md) — the wider Recall subsystem.
- Sibling: [`memory-briefing.md`](memory-briefing.md) — the live
  shelf inventory the open taxonomy leans on.
- Tests: [`RecallToolTest.java`](../../src/test/java/brainjar/recall/RecallToolTest.java).
