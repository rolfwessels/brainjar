# TODO

Running list of known gaps and follow-ups.

## Recall / Knowledge Graph

- **Populate the KG during day-to-day agent operations.** _Shipped as the
  hybrid extractor + narrow query tools — see
  [`docs/features/kg-population-rework.md`](docs/features/kg-population-rework.md)._
  Explicit triple API (Option A) was deliberately skipped to keep the page
  as the sole anchor for facts.

  Leftover work carried over from the plan
  ([`.cursor/plans/kg_population_rework_706d58b1.plan.md`](.cursor/plans/kg_population_rework_706d58b1.plan.md))
  — see the feature doc's
  [Known limitations / follow-ups](docs/features/kg-population-rework.md#known-limitations--follow-ups)
  and [Trade-offs accepted](docs/features/kg-population-rework.md#trade-offs-accepted)
  sections for the full reasoning:

  - **Unify `--mine` with the async queue.** Miner still extracts
    synchronously; save path goes through the queue. Pick one pipeline.
    (`src/main/java/brainjar/recall/ingest/Miner.java`,
    `src/main/java/brainjar/recall/kg/extract/async/ExtractionQueue.java`)
  - **Direct tests for `ExtractionWorker` + `ExtractionQueue`.** Currently
    only covered indirectly via boot-level smoke.
  - **Tune `ExtractorSignals.DEFAULT_THRESHOLD`** on real-corpus
    extraction logs (currently `3`, a guess).
  - **Grow `Predicate` vocabulary** only when LLM rejection logs pile up.
    Closed vocabulary deliberately starts tiny.
  - **Revisit predicate kind tagging.** `owns` is currently multi-valued
    to sidestep ownership-context ambiguity; may want functional later.
  - **Real alias resolution on `entities.canonical_id`.** Column exists,
    no merge UI / discovery flow yet — supersession flapping (e.g.
    "Acme" vs "Acme Corp" on `works_at`) is the motivating case.
  - **`--remine` timeout / status streaming.** Hard-coded 5-minute wait
    in `RecallCommand.executeRemine`; a larger corpus should stream
    progress instead of blocking on `awaitIdle`.
