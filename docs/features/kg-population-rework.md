# Feature: KG population rework

## Goal

Turn the knowledge graph from a wall of generic `MENTIONS` noise into a
set of typed, superseded facts with provenance — populated by a
background extractor pipeline and queried by four narrow agent tools.
Every fact stays anchored to a source page so forget/move/remine keep
working.

## Context

Before this change the KG had three problems:

1. **Every fact was a `MENTIONS` triple.** `Miner.populateKg`
   (`src/main/java/brainjar/recall/ingest/Miner.java:113`) hardcoded the
   predicate and derived objects from a capitalisation heuristic in
   `SummaryCompressor`. The graph was vocabulary-free and brittle.
2. **User memories bypassed the KG entirely.** `remember` /
   `rememberMany` stored pages via `PageStore.store` without ever calling
   an extractor. Only CLI-mined docs ever produced triples.
3. **No lifecycle.** Nothing tracked whether a page had been extracted,
   at what version, or with which content — re-mining was all-or-nothing.

`TODO.md` had three options on the table (explicit triple tool,
save-time extraction, hybrid). After a design conversation with Perry,
we picked "hybrid with narrow query tools" and explicitly dropped the
explicit triple tool.

## Goals / non-goals

**Goals**

- One extraction pipeline shared by mined docs and user captures.
- Closed predicate vocabulary — no phrasing drift.
- Provenance on every triple so `graphExplain` can surface the source.
- Supersession for functional predicates (`works_at`, `has_role`, …):
  newer facts automatically close older conflicting ones.
- Idempotent extraction so re-mining and re-saving are cheap no-ops.
- Narrow agent query tools (`graphFacts`, `graphNeighbors`,
  `graphHistory`, `graphExplain`) instead of raw graph access.
- Low-signal pages never produce triples — summary-only, not half-facts.

**Non-goals**

- **Explicit triple tool.** Option A from `TODO.md` was rejected: a
  page-less write path fights the `source_page_id` lifecycle. If you
  can't tie a fact to a memory page, it doesn't belong in the KG.
- **Full alias graph.** `entities.canonical_id` exists; alias discovery,
  merge UI, and cross-language aliasing do not.
- **Durable queue.** `extraction_state` is the real durable record; the
  in-memory `ExtractionQueue` is just "what's pending right now".
- **User-scoped KG.** Graph stays global; provenance links triples to
  pages, pages to users via the shelf prefix.
- **Coreference resolution within a page.** Left to the LLM inside a
  single extraction call.

## What was built

| Component                                                    | Role                                                                                   |
| ------------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| `brainjar.recall.kg.extract.Extractor`                       | Interface: `ExtractionResult extract(Page)` + `String version()`                       |
| `brainjar.recall.kg.extract.ExtractionResult`                | `Summary` (always) + `List<Triple>` (may be empty)                                     |
| `brainjar.recall.kg.extract.MentionsExtractor`               | Legacy fallback when no `ChatModel` bean is available                                  |
| `brainjar.recall.kg.extract.ExtractorSignals`                | Cheap heuristic score over the `Summary` to decide LLM-or-skip                         |
| `brainjar.recall.kg.extract.LlmExtractor`                    | Prompts the `ChatModel` with the closed vocabulary, parses JSON, validates             |
| `brainjar.recall.kg.extract.HybridExtractor`                 | Glue: signals → LLM, else summary-only                                                 |
| `brainjar.recall.kg.Predicate`                               | Closed vocabulary (10 predicates), each tagged functional or multi-valued              |
| `brainjar.recall.kg.Entity`                                  | Strengthened `normalizeId` (punctuation + apostrophe handling)                         |
| `brainjar.recall.kg.KnowledgeGraph.upsertWithSupersession`   | Transactional per-page upsert with short-circuit, stale-close, and functional override |
| `brainjar.recall.kg.KnowledgeGraph` neighbors/history/explain | Query methods the tool layer calls                                                     |
| `brainjar.recall.kg.extract.async.ExtractionQueue`           | Dedup-aware bounded pending queue                                                      |
| `brainjar.recall.kg.extract.async.ExtractionWorker`          | Background thread: content-hash short-circuit, retry with backoff, upsert              |
| `RecallConfig.ExtractionLifecycle`                           | Spring `@PostConstruct`: starts worker, enqueues stale pages on boot                   |
| `RecallTool.graphFacts/Neighbors/History/Explain`            | Four new `@Tool` methods                                                                |
| `RecallCommand --remine`                                     | CLI: enqueue every page, sweep orphans, wait for the worker                            |
| `triples.extractor_version` / `entities.canonical_id`        | Additive schema columns                                                                |
| `extraction_state(page_id, extractor_version, content_hash)` | New table for idempotent re-runs                                                       |

Flow:

```text
remember/rememberMany --\
                         >-- PageStore.store --> ExtractionQueue.enqueue ---\
CLI --mine --> Miner ---/                                                    >-- ExtractionWorker
CLI --remine --> Miner.remineAll --> ExtractionQueue.enqueue (all pages) ---/          |
                                                                                        v
                                                            content_hash ?= extraction_state.content_hash
                                                                                        |
                                                                   hit: no-op           miss:
                                                                                        v
                                                                  HybridExtractor(page)
                                                                          |
                                                     signals < threshold  |  signals >= threshold
                                                                 \        |        /
                                                                  \       |       v
                                                                   \      |   LlmExtractor(ChatModel)
                                                                    v     v        |
                                                                SummaryStore.put   |
                                                                                   v
                                                  KnowledgeGraph.upsertWithSupersession
```

Key files:

- [`src/main/java/brainjar/recall/kg/extract/`](../../src/main/java/brainjar/recall/kg/extract/)
- [`src/main/java/brainjar/recall/kg/KnowledgeGraph.java`](../../src/main/java/brainjar/recall/kg/KnowledgeGraph.java)
- [`src/main/java/brainjar/recall/kg/Predicate.java`](../../src/main/java/brainjar/recall/kg/Predicate.java)
- [`src/main/java/brainjar/recall/RecallConfig.java`](../../src/main/java/brainjar/recall/RecallConfig.java)
- [`src/main/java/brainjar/recall/RecallTool.java`](../../src/main/java/brainjar/recall/RecallTool.java)
- [`src/main/java/brainjar/recall/RecallCommand.java`](../../src/main/java/brainjar/recall/RecallCommand.java)
- Plan: [`kg_population_rework_706d58b1.plan.md`](../../.cursor/plans/kg_population_rework_706d58b1.plan.md)

## Key decisions

### Page-as-sole-anchor over explicit triple tool

We kept `source_page_id` as the only way a fact enters the graph.
Alternatives:

- **Explicit `recall.remember-fact` tool.** Agent asserts triples
  directly.
  Pros: precise; bypasses extraction noise.
  Cons: page-less write path fights `invalidateByPageId`; agent
  phrasing drifts (LLMs are inconsistent across turns); doubles the
  number of code paths writing to the graph.
  Rejected because: Perry self-reported he'd "inevitably phrase things
  inconsistently", and we already have `remember` as the primary
  capture surface.

### Low-signal pages produce summary-only, never `MENTIONS`

A page below the `ExtractorSignals` threshold gets a `Summary` but
zero triples. This was an explicit course-correction from Perry
mid-plan: "send low-signal pages to a lightweight record like mentions,
not full KG upsert. Otherwise you'll pollute the graph with
half-facts." We removed the original `skip → MentionsExtractor`
fallback and went summary-only instead.

### Closed predicate vocabulary + `extractor_version`

Alternatives:

- **Open vocabulary.** Let the LLM emit whatever predicate it wants.
  Pros: richer graph.
  Cons: phrasing drift ("works_at" vs "employedBy" vs "employer");
  supersession becomes undecidable.
  Rejected because: the whole supersession mechanism depends on
  predicate equality.
- **LLM-chosen predicate with synonym mapping.** Map on read.
  Rejected because: you either mapping-table-forever or you let rot
  accumulate in stored data. Closed vocabulary forces the call early.

`extractor_version` means a vocabulary change is just a version bump
+ `--remine`. The `extraction_state` short-circuit on matching
`(version, content_hash)` keeps that cheap for unchanged pages.

### Supersession via functional-predicate registry

`Predicate.isFunctional(...)` is consulted inside
`KnowledgeGraph.upsertWithSupersession`: for each incoming functional
triple, any open triple with the same `(subject, predicate)` but a
different object is closed with `valid_to = new.valid_from`.

Alternatives:

- **Always upsert, never supersede.** Let callers issue explicit
  `invalidate` calls.
  Pros: simpler single-writer.
  Cons: current-state queries get muddy — two open `works_at` rows at
  once is meaningless.
  Rejected because: Perry specifically asked for supersession, and
  the extractor has no intuition about which of two conflicting facts
  is "the real one" — closing the older one on the functional bit is
  the only sane default.

### In-memory queue + durable state table

Alternatives:

- **Fully durable queue (file/SQLite-backed).**
  Pros: survives crashes.
  Cons: more moving parts; serialization of page ids; doesn't help
  with the real durability question which is "did we extract this at
  version X?".
  Rejected because: `extraction_state` is already the durable record
  of extraction progress. On boot we compare stored version vs
  current version and re-enqueue stale pages — crashes and version
  bumps are handled by the same code path.

### Tiny entity-resolution layer, not a real one

We strengthened `Entity.normalizeId` (punctuation-strip, apostrophe-
drop) and added `entities.canonical_id` (defaulting to `= id`), but
queries only follow the canonical — writes don't create aliases.

Alternatives:

- **Full alias table + merge tool.**
  Rejected because: huge scope, not needed yet. Adding the column now
  lets future aliasing be a data migration, not a schema break.

### Synchronous Miner, async save-path

The `CLI --mine` path still runs extraction synchronously inside
`Miner.enrich`. The save path (`remember` / `rememberMany`) goes
through the queue.

Alternatives:

- **Decorate `PageStore.store` so everything enqueues.**
  Pros: unified pipeline (as the plan originally described).
  Cons: `Miner.enrich` would still run synchronously, producing
  double-work (both sync extract + async extract via queue).
  Rejected because: the `Extractor` interface IS the shared pipeline;
  sharing that is enough, the queue is a save-path optimisation.
  Full unification is a future cleanup (`--mine` should drain the
  queue before exit).

## Trade-offs accepted

- **LLM cost on the first real run.** Every page above the signal
  threshold goes through `LlmExtractor` on the first `--remine`.
  Heuristic pre-filter keeps most of the corpus off the LLM path and
  `extraction_state` keeps subsequent runs cheap.
- **Supersession flapping risk.** If the LLM emits "Acme" and "Acme
  Corp" as different objects for `works_at`, they'll supersede each
  other on every run. Normalisation mitigates but doesn't solve it;
  we log supersession outcomes so flapping shows up in logs.
- **Low predicate coverage to start.** Ten predicates isn't much. We
  accept narrow-but-trustworthy over broad-but-messy, and let
  rejection logs tell us which new predicates are worth adding.
- **CLI `--mine` is still synchronous.** New mining writes through
  the extractor directly, so users see extraction happen inline.
  Differs from the save path and will be unified eventually.

## Known limitations / follow-ups

- **No alias merge flow.** `canonical_id` exists but nothing writes
  non-identity values to it yet. Stage 2 work if we care.
- **No dedicated tests for the async pipeline.** `ExtractionWorker`
  and `ExtractionQueue` are covered only indirectly via boot-level
  smoke. Worth adding direct tests when the pipeline stabilises.
- **`--remine` timeout is 5 minutes.** Fine for the current corpus.
  A larger corpus should stream status instead.
- **Tuning left.** `ExtractorSignals.DEFAULT_THRESHOLD = 3` is a
  guess. Needs real-corpus calibration once we have extraction logs.
- **Vocabulary drift risk.** If rejection logs pile up, extend
  `Predicate` rather than letting the LLM invent new edges.
- **Predicate kind tagging is editorial.** `owns` is currently
  multi-valued because we didn't want ambiguity about ownership
  contexts; might revisit.

## Operational notes

- First production boot on an existing DB: the two `ALTER TABLE`
  migrations in `KnowledgeGraph.migrate()` add `extractor_version`
  and `canonical_id` additively. Legacy `mentions` triples are
  back-filled with `extractor_version = 'mentions-v0'`.
- On every boot, `ExtractionLifecycle.enqueueStalePages` scans for
  pages missing from `extraction_state` or sitting on an older
  extractor version, and enqueues them. Expect a burst of LLM traffic
  the first time the app starts with a populated `PageStore`.
- `./brainjar --remine` forces the full flow: enqueue all pages,
  sweep orphans, wait up to 5 minutes for the worker to drain.
- Without a `ChatModel` bean (no OpenAI key), `RecallConfig.extractor`
  falls back to `MentionsExtractor` so CLI-only use still works — it
  just produces the legacy `mentions` triples.
- Log lines worth watching:
  - `ExtractionWorker started (extractor version=...)` — boot
  - `Extraction pageId=... inserted=... closedStale=... superseded=...` — per-page outcome
  - `LlmExtractor page=... kept=... rejected=...` — vocabulary rejections
  - `Giving up on page ... after 3 attempts` — hit retry ceiling

## References

- Plan: [`.cursor/plans/kg_population_rework_706d58b1.plan.md`](../../.cursor/plans/kg_population_rework_706d58b1.plan.md)
- TODO entry retired: see `TODO.md` under "Recall / Knowledge Graph".
- Related feature: [`perry-recall-integration.md`](perry-recall-integration.md) — the broader memory architecture this fits into.

---

## Stage 2 — KG export and open-triple filtering

### Goal

Export the knowledge graph to Cypher or CSV for loading into Neo4j. Exports include only currently-open facts (closed/superseded triples are excluded), and each export starts with a full graph clear so the target DB reflects the current state exactly.

### What was built

`RecallCommand --export-kg [--format cypher|csv|tsv] [<output-path>]`

| Detail | Behaviour |
|---|---|
| Default format | `cypher` |
| Default output | `~/.recall/export/kg.cypher` (or `kg/nodes.csv` + `kg/edges.csv`) |
| Open-only filtering | `KnowledgeGraph.openTriples()` — `WHERE valid_to IS NULL` |
| Entity filtering | Only entities referenced by at least one open triple are exported |
| Clear-all preamble | Cypher output opens with `MATCH (n) DETACH DELETE n;` before any MERGE/CREATE |
| Idempotent constraint | `CREATE CONSTRAINT entity_id IF NOT EXISTS …` follows the clear |

Key methods added:
- `KnowledgeGraph.openTriples()` — companion to `allTriples()`, returns only live rows.

### Key decisions

#### Clear-all over MERGE-only idempotency

The original design used `MERGE` to make re-runs non-destructive. We switched to a clear-all preamble.

- **MERGE-only (original).** Safe for incremental updates; target DB accumulates history.
  Pros: re-runnable without data loss; works if target has manual additions.
  Cons: closed triples that were already merged stay in Neo4j forever — the graph drifts from the SQLite source of truth.
  Rejected because: the export's job is to mirror the *current* state, not accumulate history. Neo4j is a view, not a second source of truth.

#### Exclude closed triples from export

`openTriples()` rather than `allTriples()` as the export source.

- **Export everything including closed.** Preserves full history in Neo4j.
  Pros: richer graph; timeline queries possible.
  Cons: entities whose only facts are closed still appear as nodes; the graph includes "Will works_at Impact" even after that triple was superseded.
  Rejected because: the export is for current-state inspection, not historical audit. Closed triples can always be queried via `graphHistory` from the bot.

### Trade-offs accepted

- **Export is destructive.** `DETACH DELETE` means any manual annotations added directly in Neo4j are lost on re-import. Accepted: Neo4j is a derived, read-only view.
- **No incremental sync.** Each export is a full replacement. Sufficient at current corpus size.

### Known limitations / follow-ups

- No `--open-only` / `--include-closed` flag. The choice is hardcoded. Could be a flag if historical exports become useful.
- Large graphs will hit Neo4j's `UNWIND` statement size limits. Not an issue at current scale; batching would be needed for > ~50k triples.
