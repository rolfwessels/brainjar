---
name: retrieval-benchmark
description: >-
  Runs the MemPalace vs Recall retrieval benchmark: reads benchmark/retrieval-questions.txt,
  executes mempalace search and ./brainjar --search per question, writes temp/mp_qN.txt
  and temp/recall_qN.txt plus temp/retrieval-report.md. Scripts live under
  .cursor/skills/retrieval-benchmark/scripts/ (run-retrieval-benchmark.sh, clear-and-mine.sh).
  Use when the user wants to compare MemPalace and Recall search output, re-run the benchmark,
  reset stores and re-mine, or extend benchmark questions.
---

# Retrieval benchmark (MemPalace vs Recall)

## Purpose

Repeatable side-by-side search over the **same question list**, saving raw CLI output for manual or scripted evaluation.

## What the script does not do

The runner **does not** clear `~/.mempalace/palace` or `~/.recall`. It only runs **search** against whatever is already indexed. Clearing those directories would wipe embeddings until you **mine** again, so reset + ingest is a separate step from “run benchmark queries.”

**Clean-slate comparison (same corpus):** run [scripts/clear-and-mine.sh](scripts/clear-and-mine.sh) with `--yes` (clears `~/.recall`, `~/.mempalace/palace`, optional `docs/mempalace.yaml` / `docs/entities.json`, then mines `docs/` into both systems), then run [scripts/run-retrieval-benchmark.sh](scripts/run-retrieval-benchmark.sh). Setup notes and scored run: [retrieval-comparison-mempalace-vs-recall.md](retrieval-comparison-mempalace-vs-recall.md) (kept under this skill, not in `docs/`, so it is not part of the benchmark corpus).

## Files

| Path | Role |
|------|------|
| [benchmark/retrieval-questions.txt](benchmark/retrieval-questions.txt) | One question per line; `#` starts a comment line |
| [scripts/clear-and-mine.sh](scripts/clear-and-mine.sh) | Destructive reset + mine `docs/` into MemPalace and Recall (`--yes` required) |
| [scripts/run-retrieval-benchmark.sh](scripts/run-retrieval-benchmark.sh) | Search benchmark runner (bash) |
| [retrieval-comparison-mempalace-vs-recall.md](retrieval-comparison-mempalace-vs-recall.md) | Setup recap, ground truth, scores (not under `docs/` — avoids leaking into mined corpus) |
| `temp/mp_q1.txt` … `temp/mp_qN.txt` | MemPalace stdout/stderr per question (line 1: `# wall-clock: …s`) |
| `temp/recall_q1.txt` … `temp/recall_qN.txt` | Recall (`brainjar`) stdout/stderr per question (line 1: `# wall-clock: …s`) |
| `temp/retrieval-report.md` | Mining block (if `mining-timings.md` exists), then search timings, index, questions, evaluation |
| `temp/mining-timings.md` | Written by `clear-and-mine.sh` (per-step mining wall-clock) |
| [temp/.gitignore](temp/.gitignore) | Ignores generated files; `temp/` exceptions in repo [.gitignore](.gitignore) |

## Prerequisites

1. **Corpus ingested** for both systems (see [retrieval-comparison-mempalace-vs-recall.md](retrieval-comparison-mempalace-vs-recall.md) Setup): e.g. mine `docs/` into MemPalace (`mempalace init` / `mempalace mine` as appropriate) and `./brainjar --mine docs --shelf docs`.
2. **`mempalace`** on `PATH` (`pip install mempalace`).
3. **`.env`** in repo root so `./brainjar` can start Spring (API keys, etc.).
4. **`MP_WING`** matches the MemPalace wing that contains the mined docs (default **`docs`** if the palace was initialised from the `docs/` folder).

## Run

From repository root:

```bash
bash .cursor/skills/retrieval-benchmark/scripts/run-retrieval-benchmark.sh
```

Optional environment overrides:

```bash
MP_WING=brainjar RECALL_SHELF=docs bash .cursor/skills/retrieval-benchmark/scripts/run-retrieval-benchmark.sh
```

Full pipeline (empty stores → mine → benchmark):

```bash
bash .cursor/skills/retrieval-benchmark/scripts/clear-and-mine.sh --yes
bash .cursor/skills/retrieval-benchmark/scripts/run-retrieval-benchmark.sh
```

## Agent workflow

1. Confirm prerequisites (or tell the user what is missing).
2. Run the script; it overwrites previous `temp/mp_q*.txt`, `temp/recall_q*.txt`, and `temp/retrieval-report.md`.
3. Open `temp/retrieval-report.md` and paired files for comparison or scoring.
4. If the user adds or edits questions, only change `benchmark/retrieval-questions.txt` and re-run.

## Notes

- Each `./brainjar --search` run starts the full Spring Boot app; a full pass is slow but matches real CLI behaviour.
- Do not commit files under `temp/` except `temp/.gitignore` (outputs stay local).
