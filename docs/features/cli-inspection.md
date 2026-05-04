# Feature: CLI inspection commands (`--list-shelves`, `--latest`, `--list-jobs`)

## Goal

Give a developer running BrainJar locally enough visibility into
the live store and scheduler to debug Perry's behaviour without
needing to read database files, parse log lines, or rely on the
size-bounded `--briefing` block.

## Context

The pre-existing CLI exposed `--mine`, `--search`, `--remove-shelf`,
and `--briefing`. Of those, only `--briefing` actually answered
"what's in there right now?" — and it's deliberately small (~150
tokens) because it's optimised to fit in the system prompt, not for
human inspection. When debugging "did Perry actually persist that
list of movies?" or "is there a stuck cron job firing every
minute?", neither `--briefing` nor `--search <guess>` was
sufficient.

The capability already existed inside `PageStore` (`recent(int)`,
shelf grouping) and `JobStore` (`all()`); only the CLI surface was
missing.

## Goals / non-goals

**Goals**

- `--list-shelves` — every shelf with its page count, sorted by size.
- `--latest [-n N] [--shelf X]` — N most recent pages, optionally
  filtered to one shelf. Show enough metadata to identify and
  forget a specific page (id, source path, capture time, snippet).
- `--list-jobs` — every scheduled job (one-shot and cron), with
  kind, fire-time / cron expression, note, and prompt preview.
- Re-use the existing `cli` Spring profile and `out()/err()`
  formatting so output is grep-able.
- `-n` works as an alias for `--max` so both flags are valid for
  any command that takes a limit.

**Non-goals**

- Per-user filtering (`--user <id>`). The CLI is "owner mode" — it
  shows everything, including user-prefixed shelves. Add a filter
  if multi-user deployments become real.
- Pretty TTY rendering / colour. The output is plain text by
  design; pipe it through `less` / `grep` like everything else.
- Mutation commands beyond what already existed (`--remove-shelf`).
  Inspection only — write paths stay in the tool layer.
- A general `--cancel-job <id>` CLI. Schedulers are still managed
  via the agent tools.

## What was built

| Component | Role |
| --------- | ---- |
| [`RecallCommand.Command{LIST_SHELVES,LATEST,LIST_JOBS}`](../../src/main/java/brainjar/recall/RecallCommand.java) | New enum entries; matching `execute*` methods. |
| [`RecallCommand#parseArgs`](../../src/main/java/brainjar/recall/RecallCommand.java) | New flags `--list-shelves`, `--latest`, `--list-jobs`; `-n` added as alias for `--max`. |
| [`RecallCommand`](../../src/main/java/brainjar/recall/RecallCommand.java) | Now also injects `JobStore` and `ScheduleProperties` (for timezone-correct fire-time formatting). |
| [`brainjar`](../../brainjar) wrapper | Routes the three new flags to the `cli` Spring profile so output uses `out()` formatting. |

Output shapes:

```text
$ ./brainjar --list-shelves
Shelves (4):
  docs (93)
  user:123:movies (12)
  notes (4)
  user:123:skills (1)

Store: 110 pages indexed.
```

```text
$ ./brainjar --latest -n 2 --shelf user:123:movies
Latest 2 page(s) on shelf "user:123:movies":

— 1 — user:123:movies
  id:     a1b2c3...
  source: capture/2026-04-18/movies.txt
  when:   2026-04-18 17:42 UTC
  
  Civil War (2024) — Alex Garland.

— 2 — user:123:movies
  ...
```

```text
$ ./brainjar --list-jobs
Scheduled jobs (2):

— job-7f3  user=123
  CRON "0 0 7 * * *"
  note:   morning-briefing
  prompt: Give me today's agenda...
```

## Key decisions

### `PageStore.recent(MAX_VALUE)` then filter, not a new store API

Both `--list-shelves` and `--latest --shelf X` need shelf-grouping
or shelf-filtering, which `PageStore` doesn't expose directly. We
considered adding `countByShelf()` and `recentByShelf(shelf, limit)`
methods, but the corpus is small (hundreds of pages, not millions)
and the existing `recent(int)` already returns the full page list
in a stable order. Filtering in the CLI keeps the store interface
unchanged.

Alternatives:

- **Add `countByShelf` / `recentByShelf` to `PageStore`.** Cleaner
  for large stores, but premature — both `InMemoryPageStore` and
  `FilePageStore` would need the new methods, and we'd still need
  the cross-shelf path for `--list-shelves`.
- **Sort by `book().lastModified()` explicitly in the CLI.**
  `recent()` already does this implicitly; re-sorting would just
  duplicate the work and make us responsible for tie-breaking.

### CLI is "owner mode" — no user filtering

The agent tools enforce per-user scoping (`UserContext` →
`user:<uid>:…` shelves). The CLI is run by whoever has shell
access to the host, which is the operator. Showing them everything
matches reality; adding a `--user <id>` filter would be useful,
but only after multi-user is a real scenario.

Alternatives:

- **Default to "owner shelves only" (strip `user:*` prefixes).**
  Hides exactly the data an operator most wants to debug.
- **Require `--all` to see user-scoped shelves.** Extra friction
  for the only realistic use case today.

### `-n` as a global alias for `--max`

`--max` was already the limit flag for `--search`. The user's
mental model for "show me the latest few entries" is `-n N` (à la
`tail -n`, `head -n`). Aliasing them keeps muscle memory while
preserving the existing flag.

Alternatives:

- **Only `--latest`-specific flag.** Inconsistent — searches and
  listings use different limit syntax.
- **Replace `--max` with `-n`.** Breaks any existing user scripts
  that use `--max`.

### Inject `ScheduleProperties` for timezone-correct timestamps

`--list-jobs` needs to render `fireAt` in a useful zone. The
scheduler already has `ScheduleProperties.zoneId()`. Pulling it
into `RecallCommand` keeps a single source of truth for "what
timezone does this app think in" and matches what users see in
the DM responses from cron jobs.

Alternatives:

- **Always render in UTC.** Simpler, mismatches what Perry shows
  to users.
- **Always render in `ZoneId.systemDefault()`.** Mismatches the
  scheduler when the two disagree.

## Trade-offs accepted

- `--latest` snippet is capped at 200 chars. Long pages are
  truncated; combine with `searchMemory` / direct page lookup if
  the full body is needed.
- `--list-shelves` does a full `recent(MAX_VALUE)` scan to count.
  O(N pages); fine at our scale, would want a real index for a
  larger corpus.
- Output is human-formatted, not JSON. Easy to read, mildly
  annoying to script against. A `--json` flag is a future option
  if needed.
- `--list-jobs` shows all users' jobs unconditionally. See the
  "owner mode" decision above.

## Known limitations / follow-ups

- No `--user <id>` filter on any of the three commands.
- No JSON / structured output format.
- No pagination on `--list-shelves` if the shelf count grows large
  (today: low double digits at most).
- `--latest` ordering inherits whatever `pageStore.recent()`
  returns — currently book `lastModified`, which lags actual
  capture time for daily-capture books that append throughout a
  day. Same caveat already noted in
  [`memory-briefing.md`](memory-briefing.md).

## Operational notes

- All three commands use the `cli` Spring profile (quiet logging,
  plain `System.out`). This is automatic via the `brainjar`
  wrapper script.
- They `System.exit(0)` on completion via `SpringApplication.exit`,
  matching the other CLI commands; safe to chain in shell scripts.

## References

- Sibling: [`memory-briefing.md`](memory-briefing.md) — the
  in-prompt counterpart; same data, smaller footprint.
- Sibling: [`scheduling.md`](scheduling.md) — owns `JobStore` and
  `ScheduleProperties` that `--list-jobs` reads.
- Tests: parsing cases in
  [`RecallCommandTest.java`](../../src/test/java/brainjar/recall/RecallCommandTest.java).
