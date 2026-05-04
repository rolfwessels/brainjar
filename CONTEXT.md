# BrainJar

A Discord-native personal agent (Perry) that captures information via DMs, stores it in a hybrid memory system, and retrieves it conversationally.

## Language

### Memory Model

**Perry**:
The agent — the conversational identity the user interacts with. Not "the bot", not "the assistant".
_Avoid_: bot, assistant, AI

**Shelf**:
A named topic or category that groups a user's memories (e.g. "movies", "notes", "profile"). User-scoped by default; some shelves are global (e.g. mined docs).
_Avoid_: category, folder, namespace, topic

**Book**:
A daily capture file for a specific user + shelf + date. Bundles pages sourced from the same file on the same day.
_Avoid_: file, document, entry

**Page**:
A content chunk — the atomic unit of stored memory. Identified by a stable SHA-256 hash of `sourcePath_chunkIndex`. Carries an embedding and a summary.
_Avoid_: chunk, record, document, item, note

**Summary**:
A compressed description of a Page, generated during ingestion. Used in briefings and context assembly.
_Avoid_: description, excerpt, abstract

**Briefing**:
A pre-assembled, cached context snapshot injected into Perry's system prompt at the start of each conversation. Represents the L0 layer of retrieval.
_Avoid_: context, summary, memory dump

### Knowledge Graph

**Triple**:
A semantic fact: (subject, predicate, object) with `validFrom`, `validTo`, and `confidence`. The unit of knowledge in the graph.
_Avoid_: fact, edge, relationship, statement

**Entity**:
A canonical, normalized name for a real-world concept (e.g. "Sara Jones" → `sara_jones`). Carries a type and creation timestamp.
_Avoid_: node, concept, subject, thing

**Predicate**:
A normalized relationship type between entities (e.g. `works_at`, `knows`). Functional predicates supersede prior facts — a new `works_at` triple closes the old one.
_Avoid_: relation, edge type, property

**Extraction**:
The pipeline that derives Triples from Pages. Two strategies: `LlmExtractor` (rich, slower) and `MentionsExtractor` (signal-based, fast), fused by `HybridExtractor`. Runs asynchronously via an `ExtractionQueue`.
_Avoid_: parsing, mining, indexing

### Retrieval

**Hybrid Search**:
RRF (Reciprocal Rank Fusion) of cosine similarity (embedding-based) and BM25 (keyword-based) over Pages. The standard retrieval path for memory queries.
_Avoid_: search, semantic search, vector search

**Layered Context**:
The four-level retrieval strategy used to assemble context for Perry: L0 briefing cache → L1 recency (5-day window) → L2 hybrid search → L3 knowledge graph facts.
_Avoid_: context window, retrieval pipeline, RAG

### Behaviour

**Skill**:
A named markdown playbook Perry can invoke or be taught. Built-in skills live on the classpath; user-taught skills are stored as Pages on a dedicated shelf.
_Avoid_: command, workflow, template, prompt

**ScheduledJob**:
A ONCE (fire-at instant) or CRON (expression-based) task that re-prompts Perry and DMs the result to the user.
_Avoid_: reminder, task, cron job, timer

**Capture**:
The act of storing new information into memory (via `remember()` or `rememberMany()`). Results in one or more new Pages on a Shelf.
_Avoid_: save, store, write, ingest (reserve "ingest" for CLI doc mining)

**Ingest**:
Bulk loading of documents from disk into a Shelf via the CLI (`--mine`). Distinct from capture (which is conversational).
_Avoid_: import, index, sync

## Relationships

- A **Shelf** contains zero or more **Books**
- A **Book** contains one or more **Pages**
- A **Page** carries one **Summary** and one embedding vector
- **Extraction** derives zero or more **Triples** from a **Page**
- A **Triple** links two **Entities** via a **Predicate**
- A **ScheduledJob** fires Perry with a prompt and DMs the reply to the user
- A **Skill** is stored either on the classpath (built-in) or as a **Page** on a skills **Shelf** (user-taught)
- **Layered Context** assembles from the **Briefing** (L0), recent **Pages** (L1), **Hybrid Search** results (L2), and **Triple** lookups (L3)

## Example dialogue

> **Dev:** "When the user says 'remember this', does that create a Page immediately?"
> **Domain expert:** "Yes — that's a Capture. Perry calls `rememberMany()`, which writes Pages to the target Shelf. Extraction is async — the Triples come later via the ExtractionQueue."

> **Dev:** "So searching memory queries both Pages and Triples?"
> **Domain expert:** "Right. `searchMemory()` runs Hybrid Search over Pages first, then enriches with graph facts from the Knowledge Graph. That's the L2 + L3 part of Layered Context."

> **Dev:** "If the user teaches Perry a new skill, where does it live?"
> **Domain expert:** "It's just a Page on the skills Shelf — same storage as everything else. The SkillRegistry reads it back when Perry needs the playbook body."

## Flagged ambiguities

- "memory" is used loosely in conversation to mean both a single **Page** and the whole memory system — resolved: prefer "Page" for the unit, "memory system" for the whole.
- "context" is overloaded (LLM context window vs. **Layered Context** vs. user context/identity) — resolved: use "Layered Context" for the retrieval strategy, "system prompt context" for what goes into the LLM, "user identity" for the ThreadLocal scope.
- "search" is sometimes used for both **Hybrid Search** (over Pages) and graph queries (over Triples) — resolved: "Hybrid Search" for the Page retrieval path, "graph query" for Triple lookups.
