# Feature: Recall — Native Java Memory System

## Goal

Give Perry a long-term, searchable memory that runs entirely locally with zero external API calls. Documents are mined into a vector store, compressed into summaries, and linked through a temporal knowledge graph. Perry accesses it all via `@Tool` methods.

## Why Build It (instead of using MemPalace)

We evaluated MemPalace (Python) in [Option A](../plans/mempalace-integration.md) and liked several of its ideas, but chose to build a native Java system because:

| Concern | Decision |
|---|---|
| No Python dependency | Recall is pure Java — same build, same deploy, same tests |
| Full control | We own the chunking, compression, and retrieval logic |
| Reusable package | `brainjar.recall` is a self-contained namespace, portable to other projects |
| Local-only | Uses ONNX-based `all-MiniLM-L6-v2-q` embeddings — no API key, fully offline |

## Ideas Cherry-Picked from MemPalace

1. **Smart chunking** — 800-char chunks, 100-char overlap, paragraph/line boundary preference
2. **AAAK-style compression** — deterministic heuristic summarisation (entities, topics, key sentence, flags) without an LLM
3. **4-layer progressive context loading** — L0 identity, L1 key memories, L2 shelf-scoped metadata, L3 full semantic search
4. **Temporal knowledge graph** — facts as triples with `valid_from`/`valid_to` for time-aware querying
5. **Stable page IDs** — `sha256(sourcePath + chunkIndex)` for idempotent re-ingestion

## Domain Model

```
Shelf  →  Book  →  Page  →  Summary
                     ↕
              KnowledgeGraph (temporal triples)
```

- **Shelf** — topic grouping (e.g. `"docs"`, `"architecture"`)
- **Book** — a source file (path, title, last-modified)
- **Page** — a text chunk with a stable ID and embedding
- **Summary** — AAAK-compressed page (entities, topics, key sentence, flags)

## Dependencies Added

```kotlin
implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.0.0-beta5")
implementation("org.xerial:sqlite-jdbc:3.49.1.0")
```

## Package Structure

```
src/main/java/brainjar/recall/
  model/          Shelf, Book, Page, Summary (records)
  ingest/         Chunker, Miner, SummaryCompressor
  search/         Searcher, LayeredContext (L0-L3)
  kg/             KnowledgeGraph, Triple, Entity
  store/          PageStore (interface), InMemoryPageStore, FilePageStore
  RecallConfig.java   Spring @Configuration (beans)
  RecallTool.java     @Tool methods for Perry
  RecallCommand.java  CLI: --mine, --search, --remove-shelf
```

## How Perry Uses It

`RecallTool` exposes two `@Tool` methods:

- `searchMemory(query)` — semantic search across all pages, enriched with knowledge graph triples
- `recall(shelf)` — shelf-scoped context retrieval

These are wired into `BrainJarAssistant` alongside `BraveSearchTool`.

## CLI

All recall CLI operations use the `brainjar` wrapper script and exit after completion (no Discord bot startup).

### Mine

```bash
./brainjar --mine /path/to/docs --shelf docs
./brainjar --mine /path/to/src /path/to/docs --shelf code
```

- `--mine` followed by one or more paths (files or directories)
- `--shelf` optional — defaults to the directory name

### Search

```bash
./brainjar --search "how does chunking work"
./brainjar --search "embeddings" --shelf docs --max 10
```

- `--shelf` optional — scopes search to a specific shelf
- `--max` optional — number of results (default 5)

### Remove shelf

```bash
./brainjar --remove-shelf docs
```

Deletes all pages belonging to the named shelf.

## Storage

- **Vector store**: LangChain4j `InMemoryEmbeddingStore` serialised to `~/.recall/embeddings.json`
- **Knowledge graph**: SQLite at `~/.recall/knowledge_graph.sqlite3`

## Test Coverage

84 tests across all components:

- `ChunkerTest` — boundary detection, overlap, merging, deterministic IDs, unicode
- `InMemoryPageStoreTest` — store, search, filter, delete, idempotent upsert
- `MinerTest` — single file, directory, re-mine idempotency, empty files
- `SummaryCompressorTest` — entity/topic/key-sentence/flag extraction
- `KnowledgeGraphTest` — CRUD, temporal filtering, deduplication, normalisation
- `SearcherTest` — ranked search, shelf filtering, KG enrichment
- `LayeredContextTest` — L0-L3 progressive loading, token budgets
- `FilePageStoreTest` — serialise/deserialise round-trip
- `MineCommandTest` — arg parsing, shelf defaults, mining integration
