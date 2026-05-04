# LangChain4j and LangSmith

Research on LLM observability options for a Java / Spring Boot stack (April 2026).

## LangChain4j

### What it is

A Java-native LLM framework, independently maintained — **not** a port of Python LangChain. It shares naming inspiration but is a completely separate codebase with Java-idiomatic design.

**1.0 GA shipped May 2025.** Production-ready for chatbot, RAG, and tool-calling use cases.

### Core concepts

| Concept | Description |
|---|---|
| `ChatModel` | Unified interface over 20+ LLM providers (OpenAI, Anthropic, Gemini, Ollama, Mistral, etc.) |
| `@AiService` | Annotate a Java interface; LangChain4j wires up an implementation automatically |
| `@Tool` | Annotate methods on Spring beans to expose them as LLM function-calling tools |
| `ChatMemory` | Per-session message history (sliding window or token budget) |
| `@MemoryId` | Scope memory to a specific user or session |
| RAG pipeline | `EmbeddingModel` → `EmbeddingStore` (30+ vector stores) → `ContentRetriever` |

### `@AiService` pattern (recommended approach)

```java
@AiService
interface BrainJarAssistant {
    @SystemMessage("""
        You are BrainJar, a personal second-brain assistant.
        Help the user capture, organise, and recall information.
        Be concise and direct.
        """)
    String chat(@MemoryId String userId, @UserMessage String message);
}
```

LangChain4j scans the classpath, finds the interface, wires in the `ChatModel` + `ChatMemoryProvider` from the Spring context, and registers it as a bean — similar to Spring Data repositories.

### Spring Boot integration

Requires **Spring Boot 3.5+**. Add both starters:

```kotlin
implementation("dev.langchain4j:langchain4j-open-ai-spring-boot-starter:1.0.0-beta5")
implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.0.0-beta5")
```

Configure via `application.yml`:

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

### Observability layers (built-in)

LangChain4j has four observability layers, from low to high level:

| Layer | Module | What it captures |
|---|---|---|
| `ChatModelListener` | core (stable) | Raw LLM request/response/error, token usage, model name |
| `@AiService` events | core (experimental) | Per-invocation lifecycle: started, request sent, response received, tools executed, completed, error |
| `langchain4j-micrometer-metrics` | separate (experimental) | `gen_ai.client.token.usage` histograms via Micrometer, tagged by provider/model/token type |
| `langchain4j-observation` | separate (experimental) | Micrometer Observation API — produces both **metrics and distributed traces**, integrates with Spring Boot Actuator + any OTel backend |

The `langchain4j-observation` module is the most complete hook and is what feeds into tracing platforms.

---

## LangSmith

### What it is

LangChain Inc's proprietary observability platform. Designed for Python LangChain and LangGraph. Provides:
- Full run tracing (prompt/response captured at every step)
- Evaluation pipelines and dataset management
- Prompt versioning
- Production monitoring dashboards

### Java / LangChain4j compatibility

**The honest answer: Java is a second-class citizen on LangSmith.**

| | Status |
|---|---|
| Official Java SDK (`langsmith-java`) | Pre-alpha — v0.1.0-alpha.23 as of early 2026 |
| Native LangChain4j integration | **Does not exist** — open issue since May 2024 ([#1104](https://github.com/langchain4j/langchain4j/issues/1104)), unresolved |
| OTLP ingestion | **Yes** — LangSmith accepts OTLP traces at `https://api.smith.langchain.com/otel` |
| Run-tree nesting (like Python) | **No** — you get flat spans, not the nested run-tree view Python users see |

LangSmith *works* via OTLP, but there is a semantic convention mismatch: LangSmith currently expects **OpenLLMetry** conventions; LangChain4j's Micrometer module emits **OTel Gen AI** conventions. A mapping layer may be needed.

### Pricing

| Plan | Included traces/month | Cost |
|---|---|---|
| Developer (Free) | 5,000 | $0 |
| Plus | 10,000 | $39/seat/month |
| Enterprise | Custom | Custom |

Self-hosting requires Enterprise license.

---

## Alternatives to LangSmith (work better with Java)

### Langfuse (recommended)

- **MIT license, fully self-hostable** (Docker Compose)
- Launched `langfuse-java` SDK in March 2025 — fetch managed prompts, push evaluation scores from Java
- Acts as a native **OTLP backend** (`/api/public/otel`) — standard OTel Java SDK points at it
- Has a first-party [Spring AI + Langfuse demo](https://langfuse.com/docs/integrations/spring-ai) directly applicable to LangChain4j
- **Free tier: 50,000 units/month**
- Best overall fit for a Java-first team

### Arize Phoenix

- Open-source for local/dev use, OTLP-native
- Listed in LangChain4j's [own observability docs](https://docs.langchain4j.dev/tutorials/observability/) as a supported integration
- 25k spans/month free tier on Arize AX (managed)

### Full OSS stack (no vendor)

```
otel-genai-bridges Spring Boot starter
  → OTel Collector → Tempo (traces) + Prometheus (metrics) → Grafana
```

Community-maintained starter that auto-instruments any `ChatModel` bean, captures prompts/completions/tool calls/cost, includes prebuilt Grafana dashboards.

---

## Recommended architecture for BrainJar

Given we are Java-first with no cross-language tracing requirement, the recommended path is **Langfuse**:

```
LangChain4j (langchain4j-observation module)
  → Spring Boot Actuator (Micrometer Observation API)
  → OTel Java SDK exporter
  → Langfuse (self-hosted Docker, or cloud free tier)
  + langfuse-java SDK for prompt versioning and evaluation scores
```

**LangSmith decision**: Skip for now. We are a second-class citizen, the Java SDK is pre-alpha, and there is no native LangChain4j integration. Revisit if this changes or if cross-language unified dashboards become a requirement.

---

## Open questions / risks

- `langchain4j-observation` is `@Experimental` — API may change between minor versions
- LangSmith OTLP convention mismatch (OpenLLMetry vs OTel Gen AI) needs verification if we ever try to use it
- Langfuse observability wiring is not part of the current LLM integration milestone — added when we need production-grade logging

## References

- [LangChain4j Observability Docs](https://docs.langchain4j.dev/tutorials/observability/)
- [LangSmith OTLP blog post](https://blog.langchain.dev/opentelemetry-langsmith)
- [langsmith-java GitHub](https://github.com/langchain-ai/langsmith-java)
- [LangChain4j issue #1104](https://github.com/langchain4j/langchain4j/issues/1104) — native integration request
- [Langfuse Java SDK launch](https://langfuse.com/changelog/2025-03-03-langfuse-java-client)
- [Langfuse Spring AI docs](https://langfuse.com/docs/integrations/spring-ai)
