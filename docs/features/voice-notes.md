# Feature: Voice notes in Discord DMs

## Goal

Let Perry handle Discord voice messages in DMs. The attached `.ogg` audio
is transcribed via OpenAI and fed into Perry's chat pipeline as if the
user had typed the transcript. End-to-end invisible: the user sends a
voice note, Perry replies to its contents.

## Context

`DirectMessageListener` read `message.getContentDisplay()` and handed
that string to the assistant. A Discord voice message carries no text —
just an `IS_VOICE_MESSAGE`-flagged attachment — so Perry received an
empty prompt and produced a confused reply.

Discord does not surface a transcript through the bot API (the mobile
client transcribes on-device, but that text isn't part of the message
payload). So any bot that wants voice-note content has to transcribe
itself.

## Goals / non-goals

**Goals**

- Detect Discord voice messages in DMs, transcribe them, and route the
  transcript through the existing `assistant.chat(userId, text)` flow.
- Fail gracefully: friendly DM replies for too-large / too-long / blank
  / failed cases, logged distinctly from normal DM flow.
- Stay on OpenAI (existing key, existing `langchain4j.open-ai.*` config).
- Configurable transcription model (default the cheapest reasonable one)
  without code changes.

**Non-goals**

- Voice-channel recording (Perry joining a VC and streaming audio).
  Different problem shape: `AudioReceiveHandler`, per-user PCM streams,
  VAD. Parked.
- Text-channel voice messages. DM-only for now.
- Auto-capturing voice notes into Recall. Perry can still remember via
  the existing `remember` tool if the content warrants it.
- Speaking back (TTS). One-way for now.

## What was built

| Component                         | Role                                                                          |
| --------------------------------- | ----------------------------------------------------------------------------- |
| `VoiceProperties`                 | `@ConfigurationProperties("brainjar.voice")` — model, max size, max duration  |
| `VoiceConfig`                     | Builds `OpenAiAudioTranscriptionModel` bean, reusing the LangChain4j API key  |
| `VoiceTranscriber`                | Wraps the model, applies guardrails, returns a sealed `TranscriptionResult`   |
| `DirectMessageListener` (updated) | On `isVoiceMessage()`, downloads the audio attachment and transcribes         |
| LangChain4j upgrade               | `1.0.0-beta5` → `1.13.0` core / `1.13.0-beta23` starter — unlocks the model   |

Flow on a voice-message DM:

1. `event.getMessage().isVoiceMessage()` → branch.
2. Pick first attachment with `contentType` starting `audio/`.
3. `attachment.getProxy().download()` → bytes via Discord's CDN proxy.
4. `VoiceTranscriber.transcribe(bytes, mimeType, duration)` →
   `Success | Blank | TooLarge | TooLong | Failed`.
5. On `Success`, the transcript becomes the user input to
   `assistant.chat(userId, transcript)` and delivery proceeds as normal.
   Any other case sends a one-line friendly DM and returns.

Key files:

- [`src/main/java/brainjar/discord/voice/VoiceTranscriber.java`](../../src/main/java/brainjar/discord/voice/VoiceTranscriber.java)
- [`src/main/java/brainjar/discord/voice/VoiceConfig.java`](../../src/main/java/brainjar/discord/voice/VoiceConfig.java)
- [`src/main/java/brainjar/discord/voice/VoiceProperties.java`](../../src/main/java/brainjar/discord/voice/VoiceProperties.java)
- [`src/main/java/brainjar/discord/listener/DirectMessageListener.java`](../../src/main/java/brainjar/discord/listener/DirectMessageListener.java)
- Tests: [`VoiceTranscriberTest.java`](../../src/test/java/brainjar/discord/voice/VoiceTranscriberTest.java),
  [`VoicePropertiesTest.java`](../../src/test/java/brainjar/discord/voice/VoicePropertiesTest.java)

## Key decisions

### Upgrade LangChain4j, use `OpenAiAudioTranscriptionModel` — over direct HTTP

LangChain4j is the framework we're already standardised on for OpenAI
access. Transcription arrived as `@Experimental` in a post-beta5 release
(`dev.langchain4j.model.audio.*`), so the real question was whether to
upgrade or sidestep. We upgraded.

The upgrade was nearly free: `1.0.0-beta5` → `1.13.0` (core) and
`1.13.0-beta23` (Spring Boot starters, still experimentally tagged)
required zero source changes. All 157 pre-existing tests passed on
first build. The `ChatLanguageModel → ChatModel` rename had already
landed by beta5, the embedding modules we use were already in the
post-migration `dev.langchain4j.model.embedding.onnx.*` package, and
`InMemoryEmbeddingStore` / `AiServices.builder` / `@Tool` surfaces were
unchanged.

Alternatives:

- **Direct HTTP via Spring `RestClient`.** ~40 lines of multipart
  POSTing to `/v1/audio/transcriptions`. Zero new deps.
  Pros: smallest surface, no framework upgrade. Cons: we own the HTTP
  and retry logic; inconsistent with how every other OpenAI call in the
  project is made. Rejected because the upgrade turned out to be
  cheaper than the ongoing maintenance of a hand-rolled client.
- **Spring AI starter (`spring-ai-starter-model-openai`).**
  Pros: typed API, first-class Spring integration. Cons: introduces a
  second AI framework alongside LangChain4j, two overlapping sets of
  `openai.*` properties to keep in sync. Rejected on "one framework at
  a time" grounds.
- **Local Whisper (whisper.cpp / faster-whisper sidecar).**
  Pros: no per-minute API cost. Cons: native binary or Python sidecar,
  CPU-heavy, ffmpeg for Opus. Overkill for short DM voice notes.
  Rejected on deployment-complexity grounds.

### Sealed `TranscriptionResult` over exceptions or `Optional<String>`

Transcription has five meaningful outcomes (success, blank, too-large,
too-long, failed) and the listener wants to reply differently to each.
A sealed interface keeps the switch exhaustive and lets the listener
pattern-match without string-sniffing error messages or wrapping nulls.

Alternatives:

- **Return `Optional<String>`, throw on errors.** Conflates "file too
  big" (user error, friendly reply) with "API blew up" (log + apologise)
  and with "transcript was blank" (user error, different reply). Three
  distinct recoveries fighting one channel.
- **Checked exceptions per case.** Ceremony out of proportion to the
  payload.

### Default model `gpt-4o-mini-transcribe`

Cheapest of OpenAI's current transcription family ($0.003/min vs
$0.006/min for whisper-1 and gpt-4o-transcribe), with accuracy that
matches or beats whisper-1 on short speech. Short DMs are
the overwhelming expected case. The model is env-overridable
(`VOICE_TRANSCRIPTION_MODEL`) without a rebuild.

### Download via `Message.Attachment#getProxy().download()` — over raw URL

JDA exposes both `getUrl()` and `getProxyUrl()`, plus a convenience
`getProxy().download()` that uses Discord's CDN proxy. The proxy path is
the supported way; raw URLs can expire and have less predictable MIME
handling. Cost: none — same HTTP round-trip.

### Transcript is invisible — no "Heard: …" echo

The user asked for the transcript to flow through as if typed. An echo
line adds a UI beat for verification but clutters the conversation and
costs another message send. If misrecognition becomes a real problem
we can add it as a toggle later; today the cost/benefit doesn't justify
it.

## Trade-offs accepted

- **Guardrails are approximations.** Discord caps voice notes well
  below our 24 MB / 5 min defaults, so the limits will almost never
  fire. They exist as a belt for the OpenAI 25 MB ceiling, not a
  product constraint.
- **No partial / streaming transcription.** The whole file is
  downloaded, then uploaded, then the response is awaited. For short
  DM notes this is fine; for multi-minute audio it adds latency the
  user sees as typing delay.
- **CLI boot still instantiates the Discord beans**, including
  `VoiceTranscriber` and its `OpenAiAudioTranscriptionModel`.
  Pre-existing design quirk, surfaced here as a Spring bean-resolution
  error when `VoiceTranscriber` had two constructors (see follow-up).
  Not fixed as part of this feature.

## Known limitations / follow-ups

- The LangChain4j Spring Boot starter is still versioned `*-beta23`
  despite core being `1.13.0` GA. If that stabilises we can drop the
  `-betaNN` suffix on the starters without code change.
- No metrics yet (transcription latency, failure rate, average
  duration). Add once there's a reason to look.
- `OpenAiAudioTranscriptionModel` is flagged `@Experimental`. Expect
  its builder surface to change in later LangChain4j releases.
- Text-channel voice notes are ignored because the listener is
  DM-scoped. Easy extension when desired.

## Operational notes

- Required env vars: `OPENAI_API_KEY` (shared with chat), optionally
  `VOICE_TRANSCRIPTION_MODEL` to override the default model.
- Cost ballpark: a 20-second voice note at `gpt-4o-mini-transcribe`
  is $0.001. A one-minute note is $0.003.
- Rollout: single user today, no flag — feature is on whenever the
  bot is running.

## References

- Plan file: _n/a_ — built directly from chat discussion; see the
  `voice-notes-transcription` plan in `.cursor/plans/` if retained.
- Related: [`perry-recall-integration.md`](perry-recall-integration.md)
  for the chat flow the transcript feeds into.
- External: [OpenAI transcription API](https://platform.openai.com/docs/guides/speech-to-text),
  [JDA `Message#isVoiceMessage`](https://docs.jda.wiki/net/dv8tion/jda/api/entities/Message.html).
- Feature-doc conventions: [`../../.cursor/skills/feature-doc/SKILL.md`](../../.cursor/skills/feature-doc/SKILL.md).
