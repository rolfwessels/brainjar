# Retrieval comparison: MemPalace vs Recall (manual benchmark)

**Location:** Kept next to the [retrieval-benchmark skill](SKILL.md) so it is **not** under `docs/` and is **not** ingested when benchmarking that corpus (this file includes ground-truth answers and scores).

**Date:** 2026-04-18  
**Corpus:** `docs/` only (same tree for both systems).

## Setup (reproducible)

Automated (from repo root): `bash .cursor/skills/retrieval-benchmark/scripts/clear-and-mine.sh --yes` (same steps below).

1. **Clear Recall:** `rm -rf ~/.recall`
2. **Clear MemPalace:** `rm -rf ~/.mempalace/palace` and remove prior `mempalace.yaml` / `entities.json` under `docs/` if present.
3. **MemPalace:** `mempalace init --yes /path/to/BrainJar/docs` then `mempalace mine /path/to/BrainJar/docs`
    - Produces `docs/mempalace.yaml`, `docs/entities.json` (and indexes those files too).
4. **Recall:** `./brainjar --mine docs --shelf docs` from repo root (loads `.env`).

**Sizes after ingest:** MemPalace **94 drawers** (11 files); Recall **93 pages** (binary `logo.png` skipped).

## Ten benchmark questions

| #   | Question                                                                |
| --- | ----------------------------------------------------------------------- |
| 1   | What default chunk size and overlap does Recall use for splitting text? |
| 2   | Where do you configure the Brave Search API key for Perry?              |
| 3   | What Spring Boot version is documented for this project?                |
| 4   | What command runs MemPalace search from the terminal?                   |
| 5   | According to the vision doc what is the first milestone for the POC?    |
| 6   | What file path stores Recall embeddings on disk?                        |
| 7   | What JDA major version does the discord-java research mention?          |
| 8   | What three topic types does AAAK-style SummaryCompressor extract?       |
| 9   | What is the default OpenAI model name in sample.env?                    |
| 10  | What does the MemPalace integration plan recommend starting with?       |

**Note:** Q2, Q9 ground truth lives in **repo root** (`README.md`, `sample.env`), not under `docs/`. Both engines were scored fairly against what the **docs corpus** can support.

## Ground truth (authoritative answers)

| #   | Answer                                                                                                                                               |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | **800** characters chunk size, **100** overlap (see `docs/features/recall.md`).                                                                      |
| 2   | **`BRAVE_API_KEY`** in `.env` (documented in root `README.md` / `sample.env`, not in `docs/` alone).                                                 |
| 3   | **3.4.4** — see decision table in `docs/features/llm-integration.md` (not the “3.5+” line in the observability research doc).                        |
| 4   | e.g. `mempalace search "…"` — see `docs/guides/mempalace-local-setup.md`.                                                                            |
| 5   | First numbered milestone: **Discord bot (text first, voice later)** — `docs/vision.md` § First Milestones (POC).                                     |
| 6   | **`~/.recall/embeddings.json`** — `docs/features/recall.md` (Storage section).                                                                       |
| 7   | **JDA 6.x** (e.g. 6.3.2 in examples) — `docs/research/discord-java.md`.                                                                              |
| 8   | Ill-posed wording: AAAK lists **entities, topics, key sentence, flags** (not “three topic types”). Top hits still surface the bullet in `recall.md`. |
| 9   | **`gpt-5.2`** — `sample.env` at repo root (not in `docs/`).                                                                                          |
| 10  | **Option A** (install MemPalace locally, mine docs, test retrieval) — `docs/plans/mempalace-integration.md` TODO / narrative.                        |

## Top-1 source & scores (1–5)

Scale: **5** = top result contains the answer; **4** = answer in top 2–3; **3** = relevant doc, incomplete; **2** = weak / noisy; **1** = miss.

| #   | MemPalace top-1                                      | MP  | Recall top-1                                                                                                 | R   |
| --- | ---------------------------------------------------- | --- | ------------------------------------------------------------------------------------------------------------ | --- |
| 1   | `recall.md` — lists 800 / 100                        | 5   | `recall.md` (snippet #2 clearer)                                                                             | 5   |
| 2   | `llm-integration.md` (OpenAI, not Brave)             | 2   | `mempalace-local-setup.md` (Brave example, not env var)                                                      | 2   |
| 3   | `langchain4j-and-observability.md` stresses **3.5+** | 2   | **`llm-integration.md`** — **3.4.4**                                                                         | 5   |
| 4   | **`mempalace-local-setup.md`**                       | 5   | `mempalace-integration.md`                                                                                   | 4   |
| 5   | `mempalace-integration.md` — no vision list          | 2   | `mempalace-integration.md` first; **`vision.md` at rank ~5** with correct milestone                          | 4   |
| 6   | `recall.md` — snippet showed CLI, not Storage line   | 3   | `recall.md` — same chunking issue; **KG path** appears in snippets, **embeddings.json** not in top-5 excerpt | 3   |
| 7   | `discord-java.md` — **JDA 6.x / 6.3.2**              | 5   | `discord-java.md`                                                                                            | 5   |
| 8   | `recall.md` — AAAK bullet                            | 5   | `recall.md`                                                                                                  | 5   |
| 9   | `langchain4j-and-observability.md`                   | 2   | `openai-models.md` (no `gpt-5.2` default)                                                                    | 3   |
| 10  | `mempalace-integration.md` — Option A                | 5   | `mempalace-integration.md`                                                                                   | 5   |

**Totals:** MemPalace **36 / 50**, Recall **41 / 50**.

## Interpretation

- **Recall** wins mainly on **Q3** (correct project decision doc vs conflicting research note about Spring Boot 3.5+).
- **MemPalace** wins on **Q4** top-1 (guide with literal `mempalace search` examples).
- Both struggle when the answer is **outside `docs/`** (Q2, Q9) or when the **best chunk** is not rank 1 (Q5, Q6).
- **Chunking / ranking noise** is visible: Storage paths and “First milestones” lists sit in different chunks than CLI sections, so semantic search does not always put the answering sentence first.

## Automation next step

This run was **manual** (CLI capture). A repeatable **integration test** could:

1. Spin up **in-memory** `PageStore` + **FakeEmbeddingModel** (no ONNX).
2. Mine a **fixture copy** of `docs/` into the store.
3. Assert **top-k contains** expected file ids or substring checks per question.

That avoids shelling out to Python and keeps CI deterministic.

## Artefacts from this run

MemPalace `init` under `docs/` created:

- `docs/mempalace.yaml`
- `docs/entities.json`

Delete or keep as you prefer; they change Recall’s mined corpus if left in place.
