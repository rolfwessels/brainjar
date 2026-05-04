---
name: close-feature
description: >-
  Close out a feature branch by (optionally) writing a feature document under
  docs/features/ and squash-merging into main with a clean commit message. Use
  when the user asks to close a feature, finalize a branch, squash and merge,
  wrap up feature work, or "close out" the current branch. For this repo,
  which uses a local-only git workflow (no remote, no PRs).
---

# Closing a feature

Takes everything on the current feature branch, writes a feature doc when the
change is meaningful, then squash-merges into `main`. Local-only flow — no
remote push, no GitHub PR, no `gh` commands.

## Preconditions — verify first

Run all of these up front; refuse to proceed if any fail.

- Current branch is **not** `main` (`git branch --show-current`).
- Working tree is **clean** (`git status --porcelain` is empty). If dirty,
  STOP and ask the user to commit/stash first.
- Branch is **ahead of `main`** (`git log --oneline main..HEAD` non-empty).
- Tests pass (`./gradlew test --console=plain`). If they fail, STOP.

If any precondition fails, report which one and ask how to proceed. Don't
try to auto-fix.

## Workflow

Copy this checklist into the response and keep it updated:

```
- [ ] Preconditions verified
- [ ] Branch reviewed (scope & size)
- [ ] Feature doc decision made (write / skip / extend existing)
- [ ] Feature doc written (if decided)
- [ ] Squash commit message drafted
- [ ] User confirmed scope + message
- [ ] Squash-merged into main
- [ ] Feature branch deleted
```

### Step 1 — Review the branch

Gather, in parallel:

- `git log --oneline main..HEAD` — commits being squashed
- `git diff --stat main..HEAD | tail -20` — files & size
- `git branch --show-current` — branch name

Summarize for the user: N commits, ~X files changed, ~Y lines, main themes.

### Step 2 — Decide about a feature doc

Use judgement. Rough gate:

| Signal                                           | Doc? |
|--------------------------------------------------|------|
| New user-visible feature or subsystem            | Yes  |
| New public module / package with real surface    | Yes  |
| Architectural decision worth recording           | Yes  |
| Pure refactor, small bug fix, config tweak       | No   |
| Dependency bump, formatting, renames             | No   |
| Extends an existing documented feature           | Extend existing doc (stage section) |

If the branch mixes (big feature + small fix), doc covers the feature only;
the fix rides along silently.

If the branch mixes **multiple unrelated features**, STOP and ask the user
whether to (a) split into separate docs, (b) write one umbrella doc, or
(c) skip docs. Don't assume.

When writing a doc, follow `.cursor/skills/feature-doc/SKILL.md`. Prefer
extending an existing `docs/features/<slug>.md` when the scope overlaps,
rather than creating a sibling.

### Step 3 — Draft the squash commit message

Format:

```
<Title — imperative, capitalised, no trailing period>

<1–3 short paragraphs: what shipped, why, any notable trade-offs.>

<Optional bullets for sub-features if the branch bundled several.>

Made-with: Cursor
```

Guidelines:

- **Title**: 50–70 chars, describes the feature not the mechanics. E.g.
  `Add scheduling subsystem with cron + one-shot reminders` beats
  `Refactor JobScheduler and add tests`.
- **Body**: past tense, engineer-to-engineer. Mirror the feature doc's
  "Goal" paragraph if one was written.
- **Preserve the `Made-with: Cursor` trailer** (this repo's convention —
  see recent `git log` entries).
- **No emoji** in the title. Recent history uses a ✨ prefix inconsistently;
  omit it unless the user asks.
- **Don't** enumerate every sub-commit — that's what the squashed history
  is for. Mention major themes only.

### Step 4 — Confirm with the user

Show the user:

1. Target branch (always `main`).
2. Source branch (current).
3. Drafted commit title + body.
4. Whether a feature doc was written (and its path).

Ask for a single "go" before any git operation that mutates `main`. This is
the one point where you MUST wait for user confirmation.

### Step 5 — Squash-merge

Run sequentially (do NOT chain with `&&` across the checkout — verify each):

```bash
git checkout main
git merge --squash <feature-branch>
git commit -m "$(cat <<'EOF'
<title>

<body>

Made-with: Cursor
EOF
)"
git log --oneline -5
```

If `git merge --squash` reports conflicts, STOP and report. Don't attempt
auto-resolution — conflicts at merge time mean `main` moved under the
feature branch and the user should decide.

### Step 6 — Clean up

After the merge succeeds:

```bash
git branch -D <feature-branch>
```

Use `-D` (force) because squash-merge doesn't mark the source branch as
merged in git's eyes. Only after the commit is on `main`.

Report the final `git log --oneline -5` to confirm.

## Scope ambiguity — when to STOP

Per repo convention, STOP and ask when:

- Working tree is dirty.
- Tests fail.
- Branch contains unrelated features (ask: split docs, umbrella, or skip).
- `main` has moved and conflicts appear.
- User said "close this" but it's unclear whether they mean the whole
  branch or only recent commits.

Suggest, don't assume.

## Anti-patterns

- **Don't** use `git rebase -i` — interactive, and squash-merge achieves
  the same result without rewriting the feature branch.
- **Don't** `git push` anything — this repo has no remote.
- **Don't** amend the squash commit after the fact to add the feature doc;
  write the doc first, commit it on the feature branch, then squash.
- **Don't** skip the "confirm with the user" step, even if the message
  looks obvious. The user rule says suggest, don't assume.

## Related

- `.cursor/skills/feature-doc/SKILL.md` — how to write the doc.
- `docs/features/` — existing feature docs, useful as templates and for
  deciding whether to extend vs create new.
