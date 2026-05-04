# Plan: Per-user memory scoping (deferred Stage 4)

**Status:** deferred. Single-user today, so no functional impact, but
the leak becomes real the moment a second user DMs Perry.

## How it works today

| Operation              | Behaviour                                               |
| ---------------------- | ------------------------------------------------------- |
| `remember(...)`        | Writes under `user:<currentId>:<shelf>`.                 |
| `forget(phrase)`       | Filters to `user:<currentId>:*` before deleting.         |
| `searchMemory(query)`  | Searches **all shelves** — no user filter.               |
| `recall(shelf)`        | No user check.                                           |
| Briefing               | Lists **every** shelf in the system prompt.              |

Writes are user-scoped. **Reads and the briefing aren't.** On a
multi-user deployment, user A's captures appear in user B's prompt and
`searchMemory` results.

## How it should work

A read is allowed if the shelf is either:

1. **Not prefixed** with `user:` — treat as global (e.g. `docs`,
   `memorySampleSet`, `tech`), OR
2. **Prefixed with exactly `user:<currentUserId>:`** — the caller's
   own memories.

Anything starting `user:<otherId>:*` is filtered out before it reaches
the model.

## What changes

1. `RecallTool.searchMemory` / `recall` — filter results list by shelf
   name against the rule above (using `UserContext.getOrAnonymous()`).
2. `LayeredContext.briefing()` — same filter on the `pages` list feeding
   the shelf inventory and on `pageStore.recent(...)` feeding the
   highlights.
3. Tests for both paths, covering: global shelf visible, own shelf
   visible, other user's shelf hidden, anonymous sees only globals.

Rough size: ~40 LOC of production code + tests. No schema change. No
data migration.

## Out of scope (for this plan)

- `Shelf.ownerUserId` field + embeddings.json migration (the full
  original Stage 4). The string prefix continues to be the source of
  truth.
- `--user <id>` CLI flag, list/export, per-user KG slicing.
- Auth / ACLs beyond "is this user id mine".
