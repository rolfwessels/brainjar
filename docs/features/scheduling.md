# Feature: Scheduling subsystem

## Goal

Let users ask Perry in natural language to fire a reminder later — either
once at a specific local time or on a recurring cron schedule — and have
Perry DM them when the job fires. The user writes the prompt, Perry stores
it, and at fire time Perry re-prompts itself with that prompt and DMs the
reply back.

## Context

Before this, every interaction with Perry was synchronous: user DMs in,
Perry responds, done. There was no way to say "remind me to take the bins
out at 07:30 tomorrow" and have anything happen at 07:30 tomorrow.

The design brief was explicitly MVP: local-only, single-process, persisted
to disk so restarts don't drop jobs; no distributed queue, no cross-host
scheduling. One user with one Discord bot.

## Goals / non-goals

**Goals**

- Two job kinds: **one-shot** (fire at an instant) and **cron** (recurring,
  Spring 6-field cron).
- Exposed to Perry as `@Tool` methods so scheduling is agent-driven, not
  a slash command.
- Persistence across restart. Missed one-shots get a "you missed this"
  DM; cron jobs just pick up the next tick.
- Per-user cancellation (`listScheduledJobs` → `cancelScheduledJob(id)`).
- Unit-testable scheduler without spinning up JDA.

**Non-goals**

- Distributed / multi-host scheduling — one process owns the jobs file.
- Timezone-per-user — single app-wide timezone from config.
- Delivery retries on DM failure — one attempt, errors logged.
- Rate limiting / quota per user.
- Rich reminder UI (edit, snooze) — Perry can cancel + reschedule
  manually via the tool calls.

## What was built

New package `brainjar.schedule`. All files in
[`src/main/java/brainjar/schedule/`](../../src/main/java/brainjar/schedule/).

| Class / file | Role |
|---|---|
| [`ScheduledJob`](../../src/main/java/brainjar/schedule/ScheduledJob.java) | Record. Jobs are either `ONCE` (with `fireAt`) or `CRON` (with `cron`). Per-user via `userId`. |
| [`JobStore`](../../src/main/java/brainjar/schedule/JobStore.java) / [`FileJobStore`](../../src/main/java/brainjar/schedule/FileJobStore.java) | Interface + JSON file-backed impl. Full-file rewrite on every mutation; volumes are tiny. Default path `~/.recall/schedules.json` (configurable via `brainjar.scheduling.path`). |
| [`JobScheduler`](../../src/main/java/brainjar/schedule/JobScheduler.java) | Registers jobs with Spring's `TaskScheduler` (a dedicated `ThreadPoolTaskScheduler` bean). Keeps futures in a `ConcurrentHashMap` for cancellation. Publishes events on fire; doesn't call `JobRunner` directly. |
| [`JobRunner`](../../src/main/java/brainjar/schedule/JobRunner.java) | `@EventListener` for `JobFiredEvent` / `JobMissedEvent`. On fire: sets `UserContext`, re-prompts the assistant with `[scheduled reminder fired — …] <prompt>`, DMs the reply, deletes one-shot jobs from the store. Cron jobs stay. |
| [`JobFiredEvent`](../../src/main/java/brainjar/schedule/JobFiredEvent.java) / [`JobMissedEvent`](../../src/main/java/brainjar/schedule/JobMissedEvent.java) | Records. The handoff between `JobScheduler` and `JobRunner`. |
| [`ScheduleTool`](../../src/main/java/brainjar/schedule/ScheduleTool.java) | Perry-facing `@Tool`s: `scheduleOnce`, `scheduleRecurring`, `listScheduledJobs`, `cancelScheduledJob`. Reads the current user from `UserContext`. |
| [`MessageDeliverer`](../../src/main/java/brainjar/schedule/MessageDeliverer.java) / [`DiscordMessageDeliverer`](../../src/main/java/brainjar/schedule/DiscordMessageDeliverer.java) | Interface + JDA impl. Abstracted so the runner/scheduler are unit-testable without Discord. |
| [`JobRestorer`](../../src/main/java/brainjar/schedule/JobRestorer.java) | `@EventListener(ApplicationReadyEvent.class)`. On startup, calls `JobScheduler.register` for every persisted job. |
| [`ScheduleConfig`](../../src/main/java/brainjar/schedule/ScheduleConfig.java) / [`ScheduleProperties`](../../src/main/java/brainjar/schedule/ScheduleProperties.java) | `@ConfigurationProperties(prefix = "brainjar.scheduling")`. Owns the dedicated `ThreadPoolTaskScheduler` bean (`brainjar.scheduling.pool-size`, default 2) and the app-wide timezone (`brainjar.scheduling.timezone`, default `UTC`). |

The Perry-facing tool docs live in
[`src/main/resources/instructions.md`](../../src/main/resources/instructions.md)
— see the "scheduling" section for the natural-language description the
model actually sees.

## Key decisions

### Event-based dispatch from `JobScheduler` to `JobRunner`, not a direct field

`JobRunner` depends (transitively) on `BrainJarAssistant`, which depends
on `ScheduleTool`, which depends on `JobScheduler`. If `JobScheduler` also
held a `JobRunner`, Spring would see a wiring cycle. `JobRunner` also
depends on `MessageDeliverer` = `DiscordMessageDeliverer`, which needs
`JDA`, which needs the listeners that need `BrainJarAssistant` — same
cycle, different route.

We went with `ApplicationEventPublisher`: `JobScheduler` publishes
`JobFiredEvent` / `JobMissedEvent`, `JobRunner` is an `@EventListener`.
One edge deleted from the wiring graph, two cycles gone.

- **`@Lazy` on both fields.** Tried first. Works, but leaves two bits of
  "why is this lazy?" lore that future readers have to decode.
  Rejected because: the cycle is a runtime call-graph fact
  (a tool re-enters the assistant), and events describe that honestly.
- **`ObjectProvider<JobRunner>`.** Breaks the wiring cycle by deferring
  lookup. Smaller diff than events.
  Rejected because: same smell as `@Lazy` — you're still hiding a
  construction-time cycle behind a factory.
- **Separate "agent loop" orchestrator bean.** Split `ScheduleTool` into
  a data-only command and a higher-level coordinator that owns the fire
  handoff. Cleaner in the abstract.
  Rejected because: over-engineered for three tool methods.

### Spring `TaskScheduler` over Quartz / Akka / custom

We already had Spring. The workload is tiny (dozens of jobs, never
concurrent). Spring's `ThreadPoolTaskScheduler` plus `CronTrigger` covers
both kinds natively.

- **Quartz.** Industrial cron, persistent job store, clustered firing.
  Rejected because: added dependency and XML/JDBC config for a
  single-process app with a few reminders.
- **Akka / pekko scheduler.** Fine actor-model fit.
  Rejected because: we have zero actors elsewhere; not worth the import.
- **Hand-rolled `ScheduledExecutorService`.** Viable, but we'd
  reimplement cron parsing. Spring's `CronExpression` is already there.

### File-backed `JobStore` with full-file rewrite on mutation

`FileJobStore` keeps all jobs in a `ConcurrentHashMap` and rewrites the
whole JSON file on every `save` / `delete`. Expected working set is
O(dozens). Simpler than SQLite, durable across restart, human-readable
on disk.

- **SQLite.** Correct choice if we ever get to hundreds of jobs or
  concurrent writers. Overkill today.
- **In-memory only.** Loses jobs on restart — breaks the "I set a
  reminder for tomorrow" contract.

Matches the existing `embeddings.json` precedent for Recall.

### Missed one-shots get a notification DM, not a re-fire

If the app is offline when a one-shot's `fireAt` passes, `JobRestorer`
feeds it to `JobScheduler.register`, which sees `fireAt < now` and
publishes `JobMissedEvent` instead of `JobFiredEvent`. `JobRunner`
then sends a "heads up — I missed a reminder" DM with the original
prompt and deletes the job.

- **Fire late anyway.** What cron-style "catch-up" mode does.
  Rejected because: a reminder to "leave for the airport at 08:00" is
  useless at 10:00 and actively misleading if Perry answers as though
  it's still 08:00.
- **Silent drop.** Lose the job, no DM.
  Rejected because: the user trusted us with it.

Cron jobs are not notified — they just pick up their next natural tick.

### Two-step cancel flow (`listScheduledJobs` → `cancelScheduledJob(id)`)

Mirrors the two-step forget pattern (`findForgetCandidates` →
`forgetById`). Perry cannot guess job ids; the model must list, pick,
then cancel. Instructions tell Perry to ask the user before calling
`cancel` when intent is ambiguous.

Cancellation is also user-scoped: the tool refuses to cancel a job that
doesn't belong to the current `UserContext` user, logged as a warning.

### Single app-wide timezone

`brainjar.scheduling.timezone` (default `UTC`). `scheduleOnce(when, …)`
parses `when` as a local time in that zone; `scheduleRecurring(cron, …)`
feeds the same zone to `CronTrigger`. Per-user timezones would multiply
complexity (Perry prompt needs to state the user's zone, `wakeUp` needs
it, stored jobs need it) for a single-user app.

## Trade-offs accepted

- **No delivery retries.** DM failures are logged, not retried. A
  dropped Discord connection at fire time swallows a reminder.
- **Full-file rewrite on each save.** Fine for dozens of jobs, would
  hurt at thousands.
- **In-memory future map.** On restart, all `ScheduledFuture`s are
  rebuilt by `JobRestorer`. No issue today; noisy if the restorer
  throws mid-way.
- **Re-prompt cost.** Each fire is a full LLM round-trip through
  `BrainJarAssistant` — the assistant sees the fire marker prefix and
  generates a reply. Cheap at this volume; would matter under load.

## Known limitations / follow-ups

- **Timezone changes at runtime.** The zone is resolved once at
  registration. Changing `brainjar.scheduling.timezone` without
  restarting won't shift already-scheduled jobs.
- **Clock skew around DST.** Cron jobs use `CronTrigger` with a
  `TimeZone`; Spring handles DST, but we haven't stress-tested jobs
  that straddle a DST boundary.
- **No per-user quota.** A user who says "remind me every second"
  (valid cron) will DoS their own DMs. Cron validation is expression-
  level only.
- **Minimum lead time is 5s.** Hard-coded in `ScheduleTool.MIN_LEAD_TIME`.
  Protects against immediate firings that race the store write.
- **No edit / snooze.** Cancel + reschedule via Perry is the workflow.

## Operational notes

Configuration (all optional):

```yaml
brainjar:
  scheduling:
    timezone: Europe/Amsterdam    # default: UTC
    pool-size: 2                  # default: 2
    path: /path/to/schedules.json # default: ${user.home}/.recall/schedules.json
```

Log lines to watch in production:

- `JobScheduler`: `Registered one-shot job id=...` / `Registered cron job id=...`
- `JobRunner`: `Firing job id=...` / `One-shot job id=... removed after fire`
- `JobRunner`: `Job id=... user=... missed (fireAt=...) — notifying user, not firing`
- `JobRestorer`: `Restoring N persisted scheduled job(s)` on startup.

On restart, all persisted jobs re-register. One-shots whose `fireAt` is
in the past turn into missed-notifications and are removed.

## References

- [`src/main/java/brainjar/schedule/`](../../src/main/java/brainjar/schedule/) — package root
- [`src/main/resources/instructions.md`](../../src/main/resources/instructions.md) — Perry-facing tool docs
- [`src/test/java/brainjar/schedule/`](../../src/test/java/brainjar/schedule/) — unit + integration tests
- [`docs/features/perry-recall-integration.md`](perry-recall-integration.md) — the Recall work that established `UserContext` and the two-step-flow pattern reused here
