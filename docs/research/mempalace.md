# MemPalace Research

**Date researched:** 2026-04-16

## Overview

MemPalace (v3.3.0, MIT, ~47K GitHub stars) is a Python-based local-first AI memory system. Stores conversation history as verbatim text, retrieves via semantic search (ChromaDB). Nothing leaves your machine unless you opt in.

## Architecture

### Palace Metaphor (Method of Loci)

- **Wings**: Top-level domains (projects, people, topics)
- **Rooms**: Subtopics within a wing (e.g., "auth", "billing")
- **Halls**: Memory category labels (facts, events, discoveries, preferences, advice) — metadata only
- **Drawers**: Verbatim original content, chunked at 800 chars / 100 overlap
- **Closets**: AAAK-compressed summaries (lossy despite "lossless" claim)
- **Tunnels**: Auto-detected cross-wing connections when same room name appears in 2+ wings

### Storage

- Single ChromaDB persistent collection for all memories
- SQLite for temporal knowledge graph (entities + triples)
- Drawer IDs are deterministic: `drawer_{wing}_{room}_{md5(source_file + chunk_index)[:16]}`

### 4-Layer Memory Stack

| Layer | Content                      | Tokens    | When             |
| ----- | ---------------------------- | --------- | ---------------- |
| L0    | Identity file                | ~50-100   | Always           |
| L1    | Top-15 drawers by importance | ~500-800  | Always           |
| L2    | Wing/room-scoped recall      | ~200-500  | On topic trigger |
| L3    | Full semantic search         | Unbounded | Explicit query   |

### Write Path

Deterministic — file walking, regex-based classification, 800-char chunking. No LLM involved.

### Read Path

ChromaDB `col.query()` with optional `where` filters. No re-ranking, no BM25, no hybrid search.

## MCP Server (29 tools)

Install: `claude mcp add mempalace -- python -m mempalace.mcp_server`

Key categories:

- **Palace Reads**: `mempalace_status`, `mempalace_search`, `mempalace_list_wings`, `mempalace_list_rooms`, `mempalace_get_taxonomy`
- **Palace Writes**: `mempalace_add_drawer`, `mempalace_delete_drawer`
- **Knowledge Graph**: `mempalace_kg_query`, `mempalace_kg_add`, `mempalace_kg_invalidate`, `mempalace_kg_timeline`
- **Navigation**: `mempalace_traverse`, `mempalace_find_tunnels`
- **Agent Diary**: `mempalace_diary_write`, `mempalace_diary_read`

Transport: JSON-RPC 2.0 over stdin/stdout.

## Python API

```python
from mempalace.searcher import search_memories
results = search_memories("auth decisions", palace_path="~/.mempalace/palace")

from mempalace.knowledge_graph import KnowledgeGraph
kg = KnowledgeGraph()
kg.add_triple("Kai", "works_on", "Orion", valid_from="2025-06-01")
kg.invalidate("Kai", "works_on", "Orion", ended="2026-03-01")
```

## Java/Spring Boot Integration Options

| Approach                      | Complexity | Notes                                                                              |
| ----------------------------- | ---------- | ---------------------------------------------------------------------------------- |
| **Spring AI MCP Client**      | Medium     | Best option. STDIO transport to MemPalace MCP server.                              |
| **Subprocess/ProcessBuilder** | Low        | Spawn Python process, JSON-RPC over stdin/stdout. Manual but works.                |
| **HTTP bridge**               | Medium     | No native REST API. Would need Flask/FastAPI wrapper or Streamable HTTP transport. |
| **Shared storage**            | Low        | Read ChromaDB + SQLite directly. Fragile, not recommended.                         |

## Benchmarks (with caveats)

- **96.6% R@5 on LongMemEval** — but this is ChromaDB's embedding performance, not the palace structure
- AAAK mode drops retrieval to 84.2%
- Enabling wing/room filtering can actually decrease retrieval vs raw mode
- Very early-stage: minimal test coverage (4 test files for 21 modules)

## Limitations

- Contradiction detection: advertised but not implemented
- No multi-hop graph traversal in knowledge graph
- No decay/forgetting — memories accumulate
- No hybrid search (ChromaDB only)
- No write gating or input sanitization (prompt injection risk)
- stdout used for human-readable text — can corrupt JSON-RPC streams
- macOS ARM64 segfault reported
- Entity ID normalization is naive slug — no entity resolution

## What IS Genuinely Good

- Spatial metaphor is novel — navigable rooms/wings for organization
- 4-layer progressive loading (~170 tokens wake-up) is a real differentiator
- Zero-LLM write path = zero API cost on writes
- Local-first, no cloud dependency
- Knowledge graph with temporal validity windows

## Alternatives

- **Mem0**: More mature, LLM-based extraction, cloud-dependent, higher cost
- **Zep/Graphiti**: Proper graph with Neo4j, entity resolution, multi-hop. Heavier infra.
- **ENGRAM**: Typed memory + low token budgets, LLM-routed stores

## Primary Sources

- [GitHub Repository](https://github.com/MemPalace/mempalace)
- [Official Docs](https://mempalaceofficial.com)
- [Independent Analysis (lhl/agentic-memory)](https://github.com/lhl/agentic-memory/blob/main/ANALYSIS-mempalace.md)
- [Vectorize.io Review](https://vectorize.io/articles/mempalace-review)
- [Spring AI MCP Docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
