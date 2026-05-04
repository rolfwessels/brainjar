# Feature: LLM Integration

## Goal

Replace Perry's placeholder responses with a real AI brain using OpenAI GPT-4o via LangChain4j. Perry should understand natural language messages and maintain conversation history within a DM session.

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| LLM Provider | OpenAI GPT-4o | Most capable, widely used |
| Java LLM Framework | LangChain4j | Aligns with vision's LangChain reference; `@AiService` pattern is clean |
| Memory | `MessageWindowChatMemory` (last 20 messages, per user) | Simple sliding window, in-memory; sufficient for POC |
| Memory scope | Keyed by Discord user ID | Separate conversation per user |
| Spring Boot version | Keep at 3.4.4 | LangChain4j `1.0.0-beta5` targets Spring Boot 3.3/3.4; Spring Boot 3.5 breaks its autoconfiguration |

## Dependencies Added

```kotlin
implementation("dev.langchain4j:langchain4j-open-ai-spring-boot-starter:1.0.0-beta5")
implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.0.0-beta5")
```

## Configuration

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
      temperature: 0.7
      max-tokens: 1000
      log-requests: true
      log-responses: true
```

New environment variable: `OPENAI_API_KEY`

## Architecture

```
DirectMessageListener
  → BrainJarAssistant (@AiService)
      → OpenAiChatModel (auto-configured)
      → ChatMemoryProvider (per Discord user ID)
```

## New Files

```
src/main/java/brainjar/discord/
  ai/
    BrainJarAssistant.java      -- @AiService interface with system prompt
    AiConfig.java               -- ChatMemoryProvider bean
```

## System Prompt (persona)

Perry is a personal second-brain assistant. Concise, smart, slightly dry. Helps capture, organise, and recall information. Does not make things up.

## Out of Scope (this feature)

- Persistent memory (survives restarts) — in-memory only for now
- Structured intent extraction (save/retrieve items) — that's the next feature
- Tools / function calling
- Voice input

## Next Feature

→ [intent-parsing.md](intent-parsing.md) — extract structured intent from messages and persist to storage

---

## Stage 2 — Persistent chat memory via SQLite

### Goal

Chat history now survives process restarts. Perry resumes a conversation where it left off instead of losing the rolling window on every reboot or redeploy.

### Context

`llm-integration.md` (Stage 1) explicitly listed "Persistent memory (survives restarts)" as out of scope. That was acceptable for a POC, but the immediate pain in practice was Perry losing context every time the app was restarted for a deploy or crash. The 20-message window is valuable only if it persists.

### Goals / non-goals

**Goals**
- Rolling window persists to the existing SQLite database, zero new infrastructure.
- Transparent to `AiServices` / the rest of the call stack.
- `/clear-session` still wipes both in-memory and persisted history.

**Non-goals**
- Long-term semantic memory compression. That's Option C (auto-summarise to recall), deferred.
- Per-channel or per-guild scoping. Memory key stays Discord user ID.
- Message-level audit log. The table stores the current window only, not full history.

### What was built

| Component | Role |
|---|---|
| `SqliteChatMemoryStore` | Implements LangChain4j `ChatMemoryStore`; serialises messages to JSON via `ChatMessageSerializer` / `ChatMessageDeserializer` |
| `KnowledgeGraph.chat_history` | New table: `(memory_id PK, messages TEXT, updated_at TEXT)` |
| `KnowledgeGraph.getChatMessagesJson` / `updateChatMessagesJson` / `deleteChatMessages` | CRUD over the table |
| `ChatMemoryRegistry` | Now accepts a `ChatMemoryStore` by constructor; builds `MessageWindowChatMemory` with `.chatMemoryStore(store)` |

On first access after a restart, LangChain4j loads the stored JSON back into the window automatically. No changes to `AiConfig` or `DirectMessageListener`.

### Key decisions

#### SQLite over a flat file per user

Reused the existing `knowledge_graph.sqlite3` rather than writing one JSON file per user.

- **Flat file per user.** Simple to read/write.
  Pros: no JDBC overhead; trivial to inspect.
  Cons: file-per-user sprawl; no transactional safety; second storage root to manage.
  Rejected because: SQLite was already wired and the CRUD is three queries.

#### In-process cache + durable store, not durable-only

`ChatMemoryRegistry` still holds a `ConcurrentMap<Object, ChatMemory>` as a hot cache. A fully durable-only approach would hit SQLite on every message.

- **Durable-only (no map).** Each `getOrCreate` builds a fresh `MessageWindowChatMemory` from the DB.
  Pros: simpler; no stale-cache edge cases.
  Cons: a read + deserialise on every turn; `MessageWindowChatMemory` is stateful — creating a new instance for the same id would lose the in-flight window between the DB write and the next read.
  Rejected because: LangChain4j's `AiServices` resolves `ChatMemory` once per conversation turn via the `ChatMemoryProvider`; the in-memory map ensures the same instance is reused within a session.

### Trade-offs accepted

- **Window still capped at 20 messages.** Persistence doesn't change the rolling-window policy. Long-form context still depends on the recall system.
- **Last-write-wins on concurrent sessions.** If two processes share the same SQLite file they'll overwrite each other's history. Acceptable for single-process deployment.

### Known limitations / follow-ups

- `chat_history` rows are never pruned. A user who chats forever accumulates one row per user (the window is capped, so size is bounded), but the table is never vacuumed. Non-issue at current scale.
- No migration needed: `chat_history` is created via `CREATE TABLE IF NOT EXISTS` in `KnowledgeGraph.createTables()`, so existing DBs get it on first boot.

---

## Stage 3 — Ollama local LLM as a drop-in for OpenAI

### Goal

Allow Perry to run against a locally-hosted Ollama instance instead of OpenAI, so that a faster local GPU can be used for development and experimentation without consuming API credits.

### What was built

Set `OLLAMA_BASE_URL` in `.env` to activate Ollama; unset it to fall back to OpenAI. No code changes required to switch providers.

| Component | Role |
|---|---|
| `LlmConfig` | Conditionally creates an `OllamaChatModel` bean (`@Primary`, `@ConditionalOnProperty("ollama.base-url")`). When active, OpenAI's `@ConditionalOnMissingBean` autoconfiguration backs off. |
| `application.yml` | `ollama.model-name`, `ollama.timeout-seconds`, `ollama.num-ctx` with sensible defaults |
| `OllamaSmokeTest` | `@Tag("ollama")` test — run manually to verify connectivity |
| `logback-spring.xml` | Routes `dev.langchain4j.http.client.log` to `logs/http.log` (file only) to keep the console clean |

### Key decisions

#### langchain4j-ollama version must match the spring boot starter version

The original branch used `langchain4j-ollama:1.0.0-beta5` while everything else was on `1.13.0-beta23`. The mismatch caused `OllamaChatModel` to ignore system messages entirely — the `chat(List<ChatMessage>)` API wasn't fully implemented in beta5.

Fix: replaced with `langchain4j-ollama-spring-boot-starter:1.13.0-beta23` (which pulls `langchain4j-ollama:1.13.0`), matching the rest of the dependency tree.

#### numCtx must be set explicitly

Ollama's default context window is 4096 tokens. Perry's system prompt (soul + instructions + layered context + chat history) easily exceeds this. Ollama silently truncates from the **beginning**, which removes the system message first — resulting in a generic, unpersonalised response.

Default set to `32768` via `OLLAMA_NUM_CTX` env var (override in `.env`).

### Configuration

```
# .env — activate Ollama
OLLAMA_BASE_URL=http://192.168.1.101:11434
OLLAMA_MODEL=gemma4:e4b
OLLAMA_NUM_CTX=32768       # optional, default is 32768
OLLAMA_TIMEOUT_SECONDS=120 # optional, default is 120
```

Leave `OLLAMA_BASE_URL` unset to use OpenAI.

### Known limitations

- Tool calling with Ollama depends on model capability. `gemma4:e4b` supports it but may be less reliable than GPT at multi-step tool chains.
- No streaming support — `OllamaChatModel` (non-streaming) is used throughout.
