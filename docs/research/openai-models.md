# OpenAI Models Reference

> Researched: April 1, 2026. Source: [platform.openai.com/docs/models](https://platform.openai.com/docs/models)

## Current Flagship

**GPT-5.4** is OpenAI's current recommended model for complex reasoning, coding, and professional workflows (released March 5, 2026). If unsure which to use, start here.

---

## Frontier Models (Chat Completions compatible)

| Model | API String | Context | Max Output | Input / Output per 1M | Notes |
|---|---|---|---|---|---|
| GPT-5.4 | `gpt-5.4` | 1,050K | 128K | $2.50 / $15.00 | Current flagship |
| GPT-5.4 mini | `gpt-5.4-mini` | 400K | 128K | $0.75 / $4.50 | Lower latency / cost |
| GPT-5.4 nano | `gpt-5.4-nano` | 400K | 128K | $0.20 / $1.25 | Cheapest, fastest |
| GPT-5.2 | `gpt-5.2` | 400K | 128K | $1.75 / $14.00 | Previous frontier, still supported |
| GPT-5 mini | `gpt-5-mini` | — | — | $0.25 / $1.00 | Budget tier |
| GPT-4o | `gpt-4o` | 128K | 16K | — | Previous generation, widely tested |

Versioned snapshots (pin these in production for stable behaviour):
- `gpt-5.4` → `gpt-5.4-2026-03-05`
- `gpt-5.2` → `gpt-5.2-2025-12-11`

---

## Reasoning Effort

All GPT-5.x frontier models support a `reasoning_effort` parameter instead of separate "Thinking" model names:

| Value | Behaviour |
|---|---|
| `none` | No reasoning (default for most tasks, fastest) |
| `low` | Light reasoning |
| `medium` | Balanced |
| `high` | Deep reasoning |
| `xhigh` | Maximum reasoning, slowest and most expensive |

Set this per-request, not per-model. GPT-5.2-pro requires at least `medium`.

---

## Variants to Avoid for BrainJar

| Model | Why |
|---|---|
| `gpt-5.2-pro` | **Responses API only** — incompatible with LangChain4j Chat Completions. No structured outputs. 12× the cost. |
| `gpt-5.2-chat-latest` | Rolling alias, no snapshot, only 128K context / 16K output. Designed for ChatGPT, not API developers. |
| `gpt-5.2-codex` | Tuned for long agentic coding sessions in Codex environment, overkill for conversational use. |

---

## Pricing Summary

| Model | Input | Cached Input | Output |
|---|---|---|---|
| `gpt-5.4` | $2.50 | $0.25 | $15.00 |
| `gpt-5.4-mini` | $0.75 | — | $4.50 |
| `gpt-5.4-nano` | $0.20 | — | $1.25 |
| `gpt-5.2` | $1.75 | $0.175 | $14.00 |
| `gpt-5-mini` | $0.25 | — | $1.00 |

> Note: prompts exceeding 272K tokens on GPT-5.4 / GPT-5.4 pro are billed at 2× input and 1.5× output for the full session.

> **LangChain4j compatibility note:** GPT-5.2+ does not accept the `max_tokens` parameter — use `max_completion_tokens` instead. LangChain4j `1.0.0-beta5` sends `max_tokens`, so omit the `max-tokens` config property entirely to avoid a 400 error. The model defaults to its own max output (128K).

---

## BrainJar Recommendation

Use **`gpt-5.2`** as the default (`OPENAI_MODEL=gpt-5.2` in `.env`):
- Fully compatible with LangChain4j Chat Completions
- 400K context window — more than sufficient for DM conversations
- 30% cheaper on input than GPT-5.4
- Well-tested, stable snapshot available

Upgrade to `gpt-5.4` if noticeably better reasoning quality is needed. Drop to `gpt-5.4-mini` or `gpt-5-mini` if cost becomes a concern.

---

## GPT-5.2 Benchmarks vs GPT-4o

| Benchmark | GPT-4o | GPT-5.2 |
|---|---|---|
| MMLU | ~88% | 93.5% |
| GPQA Diamond | ~53% | 92.4% |
| SWE-bench Verified | ~49% | 80.0% |
| AIME 2025 | ~9% | 100% |
| FrontierMath T1-3 | ~2% | 40.3% |
