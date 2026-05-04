# MemPalace Integration Options for BrainJar

**Date:** 2026-04-16  
**Status:** Evaluating options  
**Research:** [docs/research/mempalace.md](../research/mempalace.md)

## TODOs

- [x] **Option A** — Install MemPalace locally, mine BrainJar docs, test retrieval quality as a standalone tool
- [ ] **Option D** — Quick spike: MemPalaceService via ProcessBuilder + single @Tool for Perry to search memories
- [ ] **Decide** — Evaluate results from A+D and decide on Option B (MCP integration) vs Option C (native Java implementation)

---

## Context

BrainJar today: Java 21 / Spring Boot Discord bot ("Perry") with OpenAI via LangChain4j, Brave search, and **in-memory sliding-window chat memory (20 messages, not persistent)**. The [vision](../vision.md) describes a graph-based second brain with semantic search, but no persistent storage is implemented yet.

MemPalace: Python-based local-first memory system. Genuinely novel spatial metaphor (wings/rooms/drawers), low-token wake-up (~170 tokens), zero-LLM write path, temporal knowledge graph. But: early-stage code, inflated benchmarks, missing advertised features (contradiction detection, hybrid search), and some operational gotchas (stdout corruption in MCP mode).

---

## Option A: Standalone Developer Tool (No Code Changes)

**What:** Install MemPalace locally. Mine BrainJar conversations, project files, and design docs into a palace. Use it as YOUR memory aid when working on BrainJar -- not integrated into BrainJar itself.

**How:**
- `pip install mempalace && mempalace init ~/brainjar-palace`
- `mempalace mine d:/Work/Home/BrainJar/docs/`
- Register as MCP server in Cursor for use during development
- Optionally mine Discord conversation exports

**Pros:**
- Zero risk to BrainJar codebase
- Immediate value -- searchable project history
- Tests MemPalace on real data before committing to integration
- Good for evaluating retrieval quality firsthand

**Cons:**
- Doesn't advance BrainJar's own "second brain" vision
- Perry (the bot) gains nothing
- You're the only user

**Effort:** ~30 minutes

---

## Option B: MCP Integration via Spring AI (Perry Gets Memory)

**What:** Add Spring AI MCP Client to BrainJar. Perry talks to MemPalace's MCP server over STDIO. Conversations become persistent -- Perry remembers past discussions, preferences, and facts across restarts.

**How:**
- Add `spring-ai-starter-mcp-client` dependency to `build.gradle.kts`
- Configure STDIO transport to `python -m mempalace.mcp_server` in `application.yml`
- Expose MemPalace's MCP tools as LangChain4j `@Tool` methods (or switch that layer to Spring AI)
- Update `soul.md` to instruct Perry to save/recall memories

**Pros:**
- Perry gets persistent long-term memory (the core vision)
- Knowledge graph gives Perry temporal awareness ("you said X last month")
- Low wake-up cost means minimal token overhead per conversation
- Tests the full integration path

**Cons:**
- Introduces Python runtime dependency alongside Java
- Spring AI MCP Client vs LangChain4j -- two AI frameworks in one project (potential friction)
- MemPalace's stdout corruption issue could cause MCP transport failures
- Ties persistence to a fast-moving, early-stage Python project

**Effort:** ~1-2 days  
**Risk:** Medium-high.

---

## Option C: MemPalace as Inspiration -- Build Native Java Memory

**What:** Study MemPalace's spatial metaphor and 4-layer loading, then implement a similar concept natively in Java using LangChain4j's own vector store and embedding support. No Python dependency.

**How:**
- Design a `Palace` domain model in Java (Wing, Room, Drawer as records)
- Use LangChain4j's `EmbeddingStore` interface (ChromaDB, Qdrant, or in-memory)
- Implement the 4-layer progressive loading pattern for token-efficient context
- Build a temporal knowledge graph with SQLite or H2
- Wire into Perry as `@Tool` methods

**Pros:**
- Pure Java -- no cross-language runtime
- Full control over storage, retrieval, and schema
- Leverages existing LangChain4j ecosystem (no framework mixing)
- Can cherry-pick good ideas (spatial metaphor, 4-layer loading) and skip bad ones (naive entity resolution, no hybrid search)
- Add BM25/hybrid search from the start

**Cons:**
- Significant development effort
- Reinventing what MemPalace already does (partially)
- Delays getting something working

**Effort:** ~1-2 weeks for a basic implementation

---

## Option D: Quick Experiment via CLI Subprocess (Lightest Integration)

**What:** Call MemPalace CLI commands from Java via `ProcessBuilder`. No MCP, no Spring AI. Just spawn `mempalace search "..."` and parse the output. Minimal commitment.

**How:**
- Install MemPalace on the server/dev machine
- Create a `MemPalaceService` that shells out to `mempalace search`, `mempalace mine`, etc.
- Parse stdout (text output) and feed results into Perry's context
- Wire as a LangChain4j `@Tool`

**Pros:**
- Simplest possible integration -- no new frameworks
- Tests whether MemPalace retrieval is useful for Perry in practice
- Easy to rip out if it doesn't work
- Answers "is the retrieval quality good enough?" quickly

**Cons:**
- Fragile -- depends on CLI output format
- No access to knowledge graph tools (CLI doesn't expose all 29 MCP tools)
- Process spawning on every query adds latency
- Not production-grade

**Effort:** ~2-4 hours

---

## Recommendation

Start with **A + D in parallel**:

1. **Option A** immediately -- mine your docs and conversations, use MemPalace in Cursor. This tells you whether the retrieval is actually useful.
2. **Option D** as a quick spike -- wire a single `@Tool` that searches MemPalace from Perry. This tells you whether the integration has value for the bot.
3. Based on those results, decide between **B** (if MemPalace proves useful and you accept the Python dependency) or **C** (if you like the concepts but want to own the implementation).

This avoids over-investing before you know if the retrieval quality and spatial metaphor actually help your use case.
