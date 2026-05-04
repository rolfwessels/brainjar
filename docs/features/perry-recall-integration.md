# Feature: Perry ↔ Recall integration

## Goal

Turn Recall from a standalone Java memory library into something Perry
actually uses during a Discord conversation: identity loaded, tool usage
documented, per-message user identity carried through to tools, capture
and retraction from chat, and a retrieval integration test that runs
without the ONNX model.

This document covers **Stages 1–4** of the four-stage plan in
[`.cursor/plans/recall_roadmap_for_perry_04a94919.plan.md`](../../.cursor/plans/recall_roadmap_for_perry_04a94919.plan.md),
plus the later **Stage 3.5** tool-surface refinement. Stage 4 finally
delivers per-user read scoping, hides the internal `user:<UID>:` shelf
prefix from Perry, and replaces the lossy "find candidates → forget →
re-remember" move pattern with a single atomic `moveToShelf` tool.

## What changed

### `BrainJarAssistant` wiring

Before, the assistant was annotated with `@AiService` and a single
`@SystemMessage(fromResource = "soul.md")`. We now build it manually in
[`AiConfig`](../../src/main/java/brainjar/discord/ai/AiConfig.java) using
`AiServices.builder(...)`. That unlocks `systemMessageProvider`, which lets
us compose the system prompt from two separate resources at runtime.

The annotation-based path in langchain4j 1.0.0-beta5 does not support
`systemMessageProvider`, and `@SystemMessage` only accepts a single
`fromResource`. A manual builder is the only supported way to keep the two
files apart without a build-time codegen step.

### `soul.md` vs `instructions.md` split

| File               | Purpose                                            | Changes when…                 |
| ------------------ | -------------------------------------------------- | ----------------------------- |
| `soul.md`          | Who Perry is — personality, voice, values          | Perry's personality evolves   |
| `instructions.md`  | How Perry uses tools, the shape of memory, examples | Tools, taxonomy, or workflows change |

Keeping these separate means we can revise tool mechanics without rewriting
Perry's personality, and vice versa. The two files are concatenated with a
horizontal-rule separator before being handed to the model.

`instructions.md` has three sections:
1. **Tools & when to reach for what** — memory vs web; ambiguity rules.
2. **The shape of memory** — `Shelf → Book → Page`, `Summary`, `KnowledgeGraph`.
3. **Classification examples** — worked examples drawn from a real-world
   ChatGPT memory export (see `memorySampleSet/memory_export.md`). Includes
   a re-classification example showing preference supersession.

### `UserContext` (ThreadLocal)

[`brainjar.context.UserContext`](../../src/main/java/brainjar/context/UserContext.java)
is a tiny ThreadLocal-backed holder for the current user id.
[`DirectMessageListener`](../../src/main/java/brainjar/discord/listener/DirectMessageListener.java)
sets it before calling `assistant.chat(...)` and clears it in `finally`.

#### Why ThreadLocal (and the limitation)

LangChain4j `@Tool` methods don't get the `@MemoryId` passed through — they
have whatever arguments the model supplies. A side channel is required.
ThreadLocal is the simplest safe option **because JDA invokes our listener
synchronously and the LangChain4j tool chain runs on the same thread**.

If we ever move to a `StreamingChatModel` or hand off the chat call to a
pool, this must be revisited — either using a reactive
context-propagation mechanism or making tool args carry the user id
explicitly. The risk is flagged in the feature plan for Stage 4.

For now, `RecallTool` uses it only for logging; Stage 4 adds per-user
shelf scoping on reads.

### `LayeredContext` identity wiring

Previously `RecallTool` passed `identity = null` to `LayeredContext`, so
the L0 identity block (`wakeUp`) was never populated and `soul.md` was
not part of the long-term context layer.

[`RecallConfig`](../../src/main/java/brainjar/recall/RecallConfig.java) now
registers `LayeredContext` as a Spring bean, with `identity` loaded from
`classpath:soul.md` via `@Value`. The same `soul.md` resource feeds both
the system message (via `AiConfig`) and the L0 context (via `RecallConfig`).

### `LayeredContext` L1 query fix

`appendL1` was doing a semantic search against the fixed string
`"important key essential core"` — meaningless, especially for corpora
that don't use those words. It now uses a new `PageStore.recent(int)`
method that returns the most recently modified pages. This is a simple
and useful heuristic for "what's top-of-mind" without needing relevance
scoring or explicit "pinned" flags. Revisit in Stage 3 once summaries
and a `pinned` flag exist.

### Structured logging on `RecallTool`

Both `@Tool` methods now log a single structured line on every call:
`user`, `query`/`shelf`, result counts, and elapsed time. Makes it
obvious in a Discord log whether Perry is actually reaching for memory.

### Retrieval integration test

[`RetrievalIntegrationTest`](../../src/test/java/brainjar/recall/RetrievalIntegrationTest.java)
mines a tiny fixture corpus under
[`src/test/resources/recall-fixtures`](../../src/test/resources/recall-fixtures)
using `FakeEmbeddingModel` + `InMemoryPageStore`, then asserts structural
correctness (indexed, shelf filter, recency) and loose top-k membership
for a handful of queries mirroring the benchmark questions.

It deliberately stays loose on semantic ranking — `FakeEmbeddingModel` is
a character-bucket stub, not a semantic model, and the point here is to
prove plumbing, not replace the MemPalace-vs-Recall benchmark. Stage 3's
hybrid-search work will likely add a token-based fake for sharper
retrieval-quality assertions.

## Decisions and alternatives

### Keep `@AiService` annotation vs. manual builder

We went **manual builder** because annotation-based @AiService in
1.0.0-beta5 can't stack system messages. Alternatives considered:

- **Gradle `processResources` task** to concatenate the two files into one
  synthetic classpath resource. Cleaner annotation but adds build magic.
- **Inline `value = {...}`** on `@SystemMessage`. Loses the
  "file on disk" property and conflates personality with tooling prose.

The manual builder is slightly more code but keeps both source files
first-class editable assets.

### ThreadLocal vs. explicit tool argument

We went **ThreadLocal** because it doesn't leak `userId` into every
`@Tool` signature — the model does not need to see it. Alternative was
adding a `userId` parameter to each tool, which pollutes the tool
interface and risks the model hallucinating user ids.

The cost is the synchronous-thread assumption noted above.

### Recency for L1 vs. "pinned" flag

We went **recency** because it's zero-schema and immediately useful.
A `pinned` flag or an LLM-chosen set of "key memories" is better but
non-trivial; recency is a reasonable proxy for "what's top-of-mind".
Planned to be revisited in Stage 3 when summaries land.

## Stage 2 — capture from chat

### `remember(content, shelf)`

Perry classifies what the user says into a shelf (per the taxonomy in
`instructions.md`) and calls `remember`. The tool:

1. Reads `userId` from `UserContext`.
2. Normalises the shelf label (lowercase, non-alnum → `-`; defaults to `notes`).
3. Prefixes the shelf with `user:<userId>:` to keep user data segregated from
   global docs shelves until Stage 4 replaces the prefix with a real
   `Shelf.ownerUserId` field.
4. Resolves (or creates) a daily capture book per `(user, shelf, date)` at
   `captures/<userId>/<shelf>/<date>.md` — so multiple captures on the same
   shelf in the same day append into one book rather than creating many.
5. Picks the next available chunk index for that book via a new
   `PageStore.nextChunkIndex(Book)` helper, so page ids don't collide.
6. Stores exactly one `Page`.

One `remember` call produces one `Page`. The chunker is *not* used here —
users type short memories, not 5000-char documents.

### `forget(phrase)`

Perry calls `forget("louder volume")` when the user supersedes or retracts
something. The tool:

1. Searches the store for the phrase (top 20).
2. Filters to shelves starting with `user:<currentUserId>:`, so we never
   delete mined documentation or another user's memories.
3. Deletes the top match via a new `PageStore.deletePage(String pageId)`
   method.
4. Closes any open knowledge-graph triples whose `source_page_id` matches,
   via a new `KnowledgeGraph.invalidateByPageId(pageId, endDate)` — we set
   `valid_to` rather than physically deleting, so temporal queries still
   work.

### Decisions

- **Phrase-based `forget` rather than id-based.** Asking an LLM to pass a
  hex page id around the conversation is fragile. Phrase lookup is more
  natural for the model and the user. Collision risk is mitigated by
  filtering to user-scoped shelves and capping at the top-1 match; the
  instructions tell Perry to ask-before-deleting when the phrase is vague.
- **Shelf prefix rather than `ownerUserId` now.** Real per-user scoping on
  the `Shelf` record is a Stage 4 change that requires migrating the
  existing `embeddings.json`. A prefix costs us a slightly uglier shelf
  name today and gets out of the way of Stage 4 when we rip it out.
- **Daily book per shelf per user.** Alternatives considered: one big
  book per user (too coarse — everything shows up in every recall), or
  one book per fact (too fragmented — defeats recall-by-book). Daily
  per-shelf strikes the balance and mirrors how a physical journal works.
- **No chunking on capture.** Chunker is designed for large mined
  documents. Chat memories are one-liners; chunking them would split a
  single thought across pages and hurt retrieval.

## Stage 3 — retrieval quality

The goal here is to push recall quality up from "cosine-only, no summaries,
no KG" to something that actually surfaces the right page when the user
asks a question with concrete tokens (`BRAVE_API_KEY`, library names, file
paths) **and** when they ask a fuzzy, paraphrased question.

### Shelf-filtered cosine search

`InMemoryPageStore.executeSearch` used to pull `maxResults * 3` candidates
and post-filter in Java. It now uses LangChain4j's
`MetadataFilterBuilder.metadataKey("shelf").isEqualTo(shelfName)` and asks
for exactly `maxResults` items. Fewer wasted vectors scored, correct
shelf-count regardless of result count.

### Hybrid retrieval (BM25 + cosine, fused via RRF)

Cosine similarity is weak at literal tokens — `BRAVE_API_KEY` and
`brave api key` embed to very different vectors with the MiniLM model. We
now combine two retrievers:

- [`KeywordIndex`](../../src/main/java/brainjar/recall/search/KeywordIndex.java) —
  BM25 Okapi (`k1 = 1.2`, `b = 0.75`) over a snapshot of the `PageStore`.
  Tokenisation is simple (lowercase, split on `[^a-z0-9_.-]+`, drop length
  `< 2`) and deliberately keeps identifiers like `gpt-5.2` intact.
- [`HybridSearcher`](../../src/main/java/brainjar/recall/search/HybridSearcher.java) —
  fetches `3 × maxResults` from each retriever and fuses them using
  [Reciprocal Rank Fusion](https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf)
  with `k = 60` (the canonical value). RRF is score-agnostic, so we don't
  need to normalise cosine vs BM25.

Plumbed into `LayeredContext.search(...)` and `RecallCommand` so both Perry
and the CLI search path get hybrid retrieval for free.

#### Alternatives considered

- **Weighted score fusion.** Requires calibrating cosine and BM25 into the
  same scale; the calibration depends on corpus size and token
  distribution. RRF avoids this entirely and is standard in production IR
  systems.
- **Lucene / Tantivy for BM25.** Overkill for an in-memory store with
  O(hundreds–thousands) of pages. Rebuilding the index on every query is
  cheap enough and keeps the data model single-sourced in `PageStore`.

### `SummaryStore` + `Miner` enrichment

Mining now runs `SummaryCompressor` over each freshly stored page and puts
the result in a new in-memory
[`SummaryStore`](../../src/main/java/brainjar/recall/store/SummaryStore.java).
The store is keyed by `pageId`. We chose in-memory because the compressor
is deterministic, so regenerating on cold start from `embeddings.json` is
fine; the store interface is narrow enough to swap for SQLite later.

Summaries now feed L2 of `LayeredContext` — we append a short line per
shelf (`<shelf>: <topic1, topic2, …>`) so the LLM sees what exists before
it even searches. Previously L2 was never populated.

### KG population during mining

`Miner.enrich(...)` walks each page's `Summary.entities()` and upserts
`(bookTitle, "mentions", entity)` triples via `KnowledgeGraph`, tagged
with the originating `pageId`. Cheap, but enough to make `recall(...)`
return something when asked about an entity that was mentioned in a mined
document.

This is deliberately thin — real SPO extraction from prose needs an LLM
pass. Mentions triples unblock Stage 4's per-user KG slicing without
blocking on that.

### Latency logging

`RecallCommand.executeSearch` and both `RecallTool` `@Tool` methods now
log `elapsed_ms` alongside the result count. Gives us a quick signal in
dev logs whether ONNX warm-up or hybrid fusion is dominating.

### Decisions (Stage 3)

- **RRF over linear combination** for the reason above — score-agnostic,
  no calibration, one parameter (`k`) that the literature has a canonical
  value for.
- **Rebuild keyword index per query** rather than incremental maintenance.
  Corpus is small, code stays simple, no stale-index bugs.
- **In-memory `SummaryStore`** rather than persisting a third artefact on
  disk. Regeneration is free and `embeddings.json` stays the canonical
  on-disk store.
- **`mentions` triples only** — don't over-promise structured knowledge
  from ingestion. Real relation extraction is Stage 4+.

## Stage 3.5 — safer tool surface

After the retrieval work landed, two of the Perry-facing tools were
refined based on actual usage. Neither unlocks Stage 4 per-user scoping,
but both tighten the agent/user contract.

### `listShelves()`

A new `@Tool` on
[`RecallTool`](../../src/main/java/brainjar/recall/RecallTool.java) that
returns every shelf with a page count, split into **global shelves**
(everything not prefixed with `user:`) and the **current user's shelves**.
Perry uses this when the user says "which shelves do you have?" or when
deciding which shelf to hand to `recall`.

This is the closest we've gotten to Stage 4's "list/export" bullet
without actually migrating `Shelf.ownerUserId`. It's read-only and uses
the same shelf-name-prefix convention Stage 2 established, so it was
cheap to add and will survive the eventual Stage 4 migration with only
internal changes.

### Two-step `forget`: `findForgetCandidates(phrase)` → `forgetById(pageId)`

Stage 2 shipped a single-step `forget(phrase)` that deleted the top-1
user-scoped match. In practice that was too blunt: phrases like "dinner"
or "louder" could match several memories, and the model had no way to
verify it was deleting the right one.

`forget(phrase)` is gone. Replaced by a two-step flow, mirroring the
`scheduleTool` listing/cancel pattern:

1. **[`findForgetCandidates(phrase)`](../../src/main/java/brainjar/recall/RecallTool.java)** —
   returns up to 5 user-scoped matches, each with `pageId`, shelf, and a
   preview. **Deletes nothing.** Perry is told (via `instructions.md`)
   to show these to the user and ask which to drop when intent is
   ambiguous.
2. **`forgetById(pageId)`** — deletes exactly that page, refusing if the
   page's shelf doesn't start with `user:<currentUserId>:`. Same
   KG-invalidation side effect as the old `forget` (`invalidateByPageId`).

### Decisions (Stage 3.5)

- **Two calls instead of one-call-with-confirmation.** The tool layer
  can't drive a UI confirmation on its own — that would require blocking
  on user input from inside a tool. Splitting into list + delete lets
  Perry (the LLM) decide whether to auto-pick or ask the user, guided by
  prose in `instructions.md`. Same rationale used for scheduling cancel.
- **Refuse-not-owner on `forgetById`.** A model that got the wrong
  `pageId` from anywhere (hallucination, stale context) can't delete
  another user's data. Logged as a warning, returned as a friendly
  refusal to the model.
- **`listShelves` does not filter by user on its own.** It always shows
  globals + the current user's shelves. Other users' shelves never
  appear. This matches the existing search-scope expectation without
  promising Stage 4 scoping guarantees.

## Stage 4 — per-user read scoping and atomic moves

A real Discord chat surfaced two bugs at once:

1. Perry asked "what wines do I like?" and got nothing back, even though
   four wines had just been stored on the `preferences` shelf. Then a
   minute later he answered with two of them. Then one. The same memory,
   different answers per call.
2. When the user said "move them into a wines shelf", Perry ran
   `findForgetCandidates` → `forgetById` → `remember`, lost track halfway
   through, and ended up with the wines duplicated across shelves in
   different shapes (one blob entry under `notes`, three single-item
   entries under `wines`).

Both root-cause back to Stage 3.5's deliberate non-goals. Stage 4 closes
them.

### `UserShelves` — one place that knows about the prefix

[`UserShelves`](../../src/main/java/brainjar/recall/UserShelves.java) is
a static helper that owns every translation between Perry-facing **display
names** (`wines`, `preferences`) and on-disk **storage names**
(`user:1234:wines`). Before this, the `user:<UID>:` convention was
sprinkled across `RecallTool`, `LayeredContext`, and the
`findForgetCandidates`/`forgetById` pair, each with their own slightly
different idea of what "the current user's shelves" meant.

Now every read/write site goes through `UserShelves.toStorage(...)`,
`UserShelves.toDisplay(...)`, `UserShelves.isOwnedBy(...)`, or
`UserShelves.isVisibleTo(...)`. Perry never sees the prefix in any tool
output, and never has to type it on input. The CLI keeps showing raw
storage names via a separate `LayeredContext.briefingForOperator()` path.

### User-scoped reads in `searchMemory` and `listShelves`

`RecallTool.searchMemory` now calls
`LayeredContext.search(query, k, userId)`, which filters search results to
**globals + the current user's shelves** before formatting, and converts
shelf labels to display names in the output.

`listShelves` had been "show globals + everything that starts with
`user:`". It's now "show globals + shelves owned by the current user",
delegating ownership to `UserShelves.isOwnedBy`.

`findForgetCandidates`/`forgetById` got the same treatment: candidates
are filtered to user-owned shelves, output uses display names,
`forgetById` refuses pages it doesn't own.

### `recall(shelf, query?)` instead of `recall(shelf)`

The old `recall(shelfName)` passed the shelf name as both the shelf
filter **and** the query string. That was harmless for fuzzy queries but
catastrophic when the shelf name was something like `preferences` —
nothing on the shelf had the word "preferences" in it, so the recall came
back near-empty.

The signature is now `recall(shelfName, query?)`. When `query` is blank
Perry gets a recency-ordered dump of the shelf (new
[`PageStore.recentByShelf`](../../src/main/java/brainjar/recall/store/PageStore.java)
helper). When `query` is supplied it runs a normal hybrid search but
restricted to that one shelf. Result limits were also bumped from 5 to 15
on the search path; the old cap was hiding entries even when the query
was right.

### `moveToShelf(fromShelf, toShelf)` — atomic, owner-scoped

A new `@Tool` on `RecallTool` that grabs every page on `fromShelf` (via
`recentByShelf` with a high cap), writes them to today's daily capture
book on `toShelf`, then deletes the originals. Both shelves are
translated through `UserShelves.toStorage` first, so Perry always passes
display names.

Refusal rules:

- Source must be user-owned (no moving from `docs`, `architecture`, or
  any global shelf).
- Source ≠ destination (no-op refusal so the model doesn't accidentally
  delete-then-re-add on the same shelf).
- Empty source returns a friendly "nothing to move" rather than failing
  silently.

This replaces the multi-step `findForgetCandidates` → loop
`forgetById` → loop `remember` dance Perry used to attempt for moves.
That dance regularly dropped items mid-loop because the model would
forget where it was; one atomic call removes the failure mode.

### Instructions update — "one fact, one shape, one shelf"

[`instructions.md`](../../src/main/resources/instructions.md) gained a
short rule against storing the same fact in two shapes (e.g. one blob
entry on `notes` plus per-item entries on `wines`). The Stage 4 wines
incident was a textbook case — the resulting fragmentation made every
later recall look incomplete depending on which shape the searcher
happened to surface. Pair-shaped storage was forbidden, and `moveToShelf`
documented as the way to migrate without leaving a copy behind.

Display-name-only guidance was also added: any shelf name passed to a
`@Tool` is a short label (`wines`, not `user:1234:wines`).

### Decisions (Stage 4)

- **Static `UserShelves` over a Spring bean.** Pure-function helper with
  no state and no dependencies; injecting it would just add ceremony.
  Kept package-private constants for the prefix/separator so the test
  class can pin the encoding without leaking it to callers.
- **Recency dump over re-running search with `*` query** when `query`
  is blank on `recall`. The recency dump is deterministic, doesn't pay
  the embedding cost, and matches how a human browses a shelf they
  already know exists. Hybrid search is for "I don't remember the exact
  word"; recency is for "show me what's there".
- **`moveToShelf` writes to today's daily capture book** rather than
  preserving the original book. The KG pageId references die with the
  delete anyway, and consolidating into the capture book keeps the
  user-shelf storage shape consistent with everything else `remember`
  produces. Trade-off: original `book.title`s are lost — acceptable
  because the shelf-level grouping is what Perry actually queries.
- **Bump search top-k from 5 to 15.** The Stage 4 wines bug was partly
  a top-k cliff: 4 wines + 1 blob entry was already at the edge of the
  old cap. 15 leaves headroom for real-world conversations without
  blowing the system-prompt budget noticeably.

### What's still deferred

Stage 4 does not introduce a `Shelf.ownerUserId` field — ownership is
still encoded in the shelf-name prefix. We left the migration alone
because every read/write path now goes through `UserShelves`, which is
the only place that would need to change when the field eventually
lands. A `--user` CLI flag and proper list/export commands are also
still TODO; the CLI keeps using the operator briefing path that shows
raw storage names.

## Non-goals for this work

- A real `Shelf.ownerUserId` column / field — encoded in the shelf-name
  prefix and gated through `UserShelves` instead. Migration is cheap
  later because no other code knows about the prefix shape.
- A `--user` CLI flag and per-user list/export commands.
- Real SPO relation extraction from prose — post-Stage 4, likely
  LLM-assisted and out of scope for retrieval plumbing.
