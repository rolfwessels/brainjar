---
name: batch-cleanup
description: >-
  Process a large set of items gradually using a temporary shelf and a
  self-cancelling recurring cron. Use when the user asks you to clean up,
  enrich, look up, or otherwise iterate over more items than will fit in
  a single response, or when each item needs its own external call (web
  search, lookup, etc.) and you'd hit timeouts doing them all at once.
---

# Skill: batch-cleanup

A reusable pattern for "go through this big list over time, one bite per
tick, and stop when it's done". The whole thing runs on tools you
already have (`rememberMany`, `recall`, `forgetById`,
`scheduleRecurring`, `listScheduledJobs`, `cancelScheduledJob`). The
only non-obvious trick is using the cron's `note` field as a recoverable
handle, because you cannot pick the `jobId` yourself.

## When to use this

- The user has a long backlog of items needing the same kind of work
  (enrich missing metadata, look up, summarise, deduplicate, …).
- Doing them all in one reply would either drop items, exceed the
  response budget, or hit external rate limits.
- The work is per-item idempotent — re-running on the same item is
  fine.

If the user just wants you to handle 2-3 items now, do it directly —
don't reach for this skill.

## Required tools

- `rememberMany` — seed the work shelf in one call.
- `recall(shelf)` — read what's left on each tick.
- `forgetById(pageId)` — pop a processed item.
- `scheduleRecurring(prompt, cron, note)` — drive the loop.
- `listScheduledJobs()` + `cancelScheduledJob(jobId)` — stop when done.

## The pattern

### 1. Confirm the scope with the user

One short turn. Surface:

- What you're going to do per item (one sentence).
- Roughly how many items.
- Cadence (e.g. "every 5 minutes").

Get a yes before you create anything. The user might want a different
cadence or a smaller test run first.

### 2. Seed a temporary work shelf

Pick a unique shelf name so this run can't collide with another:
`cleanup-<topic>-<YYYY-MM-DD>` (e.g. `cleanup-movies-2026-04-18`).

Populate it in one batched call:

```
rememberMany([
  {shelf: "cleanup-movies-2026-04-18", content: "Civil War (2024)"},
  {shelf: "cleanup-movies-2026-04-18", content: "Uncut Gems (2019)"},
  ...
])
```

One item per page so they can be popped individually.

### 3. Schedule the loop with a recoverable note

You cannot set a `jobId` — that's auto-assigned and you won't see it
again unless you list. The `note` field is your only durable handle.
**Always** prefix the note with `skill:batch-cleanup:` and include the
work shelf name so a future-you can find it again:

```
scheduleRecurring(
  prompt: "You're running the batch-cleanup skill on shelf
           cleanup-movies-2026-04-18. Read the shelf with recall, pick
           the oldest remaining item, look up its missing info via
           braveSearch, store the enriched version with remember on the
           original shelf (e.g. 'movies'), then forgetById the work
           page. If the work shelf is empty, list scheduled jobs, find
           the one whose note starts with
           'skill:batch-cleanup:cleanup-movies-2026-04-18', and cancel
           it. Then send the user a one-line summary of what was done.",
  cron:  "0 */5 * * * *",
  note:  "skill:batch-cleanup:cleanup-movies-2026-04-18"
)
```

The `prompt` is *your* re-prompt at fire time, so include enough context
to act without re-asking. Cadence: 5 minutes is a sensible default;
slow it down (e.g. hourly) for heavier work or rate-limited APIs.

### 4. On each tick, do exactly one item

When the cron fires, you'll be re-prompted with the text above. Do
this, in order:

1. `recall("cleanup-movies-2026-04-18")` — see what's left.
2. If empty → jump to step 5 below (cancel + summary).
3. Otherwise pick the **first** item only.
4. Do the per-item work (look up, enrich, etc.).
5. Persist the result somewhere durable (e.g. the user's real `movies`
   shelf via `remember`).
6. `forgetById(<pageId of the work item>)` to pop it from the queue.
7. Stop. Don't drain the whole shelf in one tick — that defeats the
   point of the cron.

If a single item fails (network blip, rate limit), don't pop it. Log
what went wrong in your reply and let the next tick retry.

### 5. Detect "done" and self-cancel

When step 1 returns no pages on the work shelf:

1. `listScheduledJobs()` — scan for the job whose `note` starts with
   `skill:batch-cleanup:<workShelf>`.
2. `cancelScheduledJob(<that jobId>)`.
3. Send the user one short message: "Cleanup of `<workShelf>` done —
   processed N items. Cron cancelled."
4. The work shelf will auto-vanish from `listShelves` once the last
   page is gone — no extra cleanup needed.

If you see *more than one* matching job (shouldn't happen, but defend
against it), cancel them all.

## Guardrails

- **One item per tick.** If you batch through the whole shelf on one
  fire, you've reinvented the original "do it all at once" failure
  mode.
- **Don't change shelf names mid-run.** The `note` is your handle; if
  you rename the work shelf, you orphan the cron and have to find it
  by listing.
- **Don't run two cleanups on the same shelf in parallel.** Pick a
  date-suffixed shelf name so this is automatic.
- **If the user cancels mid-run** ("stop the cleanup"), call
  `listScheduledJobs`, find the job by note, cancel it, then optionally
  ask whether to keep or wipe the work shelf.

## Example — the conversation that motivated this skill

> User: "There's a bunch of movies missing info. You hit timeouts last
> time. Can you set something every 5 minutes that updates them and
> stops when done?"

You: confirm scope → seed `cleanup-movies-2026-04-18` from the user's
list via `rememberMany` → schedule recurring with
`note="skill:batch-cleanup:cleanup-movies-2026-04-18"` and a `prompt`
that re-states the loop → at each fire, enrich one movie and pop it →
when empty, find the job by note prefix and cancel.

## Why a skill (and not just code)

The whole loop is composed from primitives Perry already has — there's
no new tool. The thing that's hard to re-derive is *the convention*:
prefixing the `note` so the cron is recoverable later, and structuring
the per-tick `prompt` so future-you can act without context. Capturing
that convention here means the next backlog of this shape is a
30-second job, not a 30-minute one.
