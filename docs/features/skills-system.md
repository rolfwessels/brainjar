# Feature: Perry skills system

## Goal

Give Perry a library of named playbooks for multi-step workflows
that don't fit naturally into a single tool call — and surface them
into Perry's awareness on every turn so they're discoverable
without having to be searched for. Two origins: built-in skills
shipped with the app and user-taught skills learned in chat.

## Context

The motivating workflow was a "batch cleanup" pattern that took
several rounds of hand-holding for Perry to derive: stash a list
into a temporary shelf, set a recurring cron job whose `note`
encodes the work-set name, have the job process N items per tick,
self-cancel when the shelf empties. Perry only got there because
the user discovered that `cron note` was the only stable identifier
he could read back at fire time (job IDs are scheduler-assigned and
not visible to the prompt).

That insight was hard-won, and Perry would lose it the next
session — there was no place for it to live. Cursor-style skills
solve exactly this: a markdown playbook keyed by a short name and
description, with the description always visible to the model so
it knows the playbook exists.

## Goals / non-goals

**Goals**

- Built-in skills shipped from `src/main/resources/skills/<slug>/SKILL.md`.
- User-taught skills via a `teachSkill` `@Tool`, stored per-user in
  `PageStore` on a dedicated shelf.
- An always-on "Available skills" catalogue (name + description)
  prepended to the system prompt — discoverability without an
  explicit search.
- A `useSkill(name)` tool that returns the full body when Perry
  decides the playbook applies.
- A canonical built-in (`batch-cleanup`) capturing the workflow
  that triggered the feature.

**Non-goals**

- Versioning of user-taught skills — `teachSkill` overwrites by
  name. Built-ins always win on name collisions.
- Skill composition / chaining — playbooks are prose, not a DSL.
- Cross-user skill sharing — a skill taught by user A is invisible
  to user B by design.
- Server-side execution of skills — the `body` is a prompt
  fragment Perry reads and acts on, not a script.
- Telemetry on skill usage (no counters, no last-used tracking).

## What was built

New package `brainjar.skill`:

| Class / file | Role |
| ------------ | ---- |
| [`SkillDescriptor`](../../src/main/java/brainjar/skill/SkillDescriptor.java) | Record `(name, description, body, Origin{BUILT_IN, USER})`. |
| [`SkillRegistry`](../../src/main/java/brainjar/skill/SkillRegistry.java) | Loads built-ins from `classpath*:skills/*/SKILL.md` at startup; exposes `list(userId)`, `find(userId, name)`, `teach(userId, …)`. Includes a minimal YAML frontmatter parser and `slug()` normaliser. |
| [`SkillTool`](../../src/main/java/brainjar/skill/SkillTool.java) | LangChain4j `@Tool` surface: `useSkill(name)`, `teachSkill(name, description, body)`. Resolves user from `UserContext`. |
| [`AiConfig#buildSkillsBlock`](../../src/main/java/brainjar/discord/ai/AiConfig.java) | Renders the per-turn "## Available skills" catalogue (sorted, capped at 20 with `+N more`), prepended in `buildSystemMessage`. |
| [`src/main/resources/skills/batch-cleanup/SKILL.md`](../../src/main/resources/skills/batch-cleanup/SKILL.md) | First built-in: temp-shelf + note-handled cron pattern. |
| [`instructions.md`](../../src/main/resources/instructions.md) | New "Skills" tool category and "scan available skills before improvising" rule. |

Storage layout:

- Built-ins: classpath-only, read once at startup into an immutable map.
- User-taught: persisted as `Page` entries on shelf
  `user:<uid>:skills`, one page per skill, content is the rendered
  `SKILL.md` (frontmatter + body). Re-teaching by name overwrites
  by deleting the prior page.

`SKILL.md` format:

```markdown
---
name: batch-cleanup
description: One-line description shown in the catalogue.
---

# Body content (prose playbook). No length cap.
```

## Key decisions

### Catalogue in the system prompt over on-demand listing

The whole reason the `batch-cleanup` insight was hard to recover is
that Perry never thought to look. Putting `name + description` in
the system prompt every turn is the cheapest possible
discoverability mechanism: it adds maybe ~30 tokens per skill and
the model sees them before deciding what to do.

Alternatives:

- **`listSkills` tool only.** Discoverability cost is one round-trip;
  Perry has to know to call it. Same failure mode as before.
- **Inject only when prompt looks "complex."** Premature
  optimisation, and the heuristic for "complex" is the hard part.
- **Cache the catalogue per session.** Doesn't help — `teachSkill`
  mid-session would go invisible until the next session.

### Built-ins from classpath, user skills from `PageStore`

Two different lifecycles: built-ins ship with the JAR and never
change at runtime; user skills are learned per-user and need
persistence + isolation. `PageStore` already gave us per-user
shelves with stable IDs and durability — re-using it kept the new
package small and meant `--list-shelves` and `searchMemory` work
on user skills for free.

Alternatives:

- **All skills in `PageStore`, seed built-ins on first run.** First-run
  seeding is fragile (what about new built-ins on the next release?
  How do users opt-out?). Rejected.
- **All skills in classpath, prohibit user skills.** Loses the
  "Perry learns from this conversation" property the user
  explicitly wanted.
- **Dedicated `SkillStore` interface mirroring `PageStore`.** Pure
  duplication with no different requirements.

### Built-in precedence on name collisions

If a user teaches `batch-cleanup` it does *not* shadow the
built-in. Reasoning: built-ins are reviewed playbooks that we ship
on purpose; allowing override would let an accidental `teachSkill`
break the canonical workflow with no warning. `teach` does still
persist the user's version (so it's not silently lost) but
`find()` returns the built-in.

Alternatives:

- **User wins.** More flexible, but the mental model "this skill
  ships with Perry" stops being true.
- **Reject `teach` on name collision.** Loud, but obstructive when
  the user just wants a personal variant.

### Minimal hand-rolled YAML frontmatter parser

Skills only need two fields (`name`, `description`) and a body.
Pulling in SnakeYAML for a 6-line `key: value` block was overkill,
especially since the existing project has no YAML dependency.

Alternatives:

- **SnakeYAML.** Pulls a dependency for a fixed two-field schema.
- **JSON frontmatter.** Less convention-y for prose-heavy
  markdown, and the user's mental model is Cursor skills which use
  YAML.

### Spring constructor disambiguation via `@Autowired`

The two-arg `SkillRegistry(PageStore, ResourcePatternResolver)`
constructor was made `public` for cross-package tests. That
created two public constructors and broke autowiring with
`UnsatisfiedDependencyException`. Annotating the single-arg
constructor with `@Autowired` was the smallest fix.

Alternatives:

- **Package-private the two-arg constructor.** Then tests need to
  live in the same package, or use reflection. Both worse.
- **Inject `ResourcePatternResolver` as a real bean.** Spring
  already provides one; doable, but a bigger change for no real
  benefit.

## Trade-offs accepted

- **20-skill cap in the prompt.** Beyond 20, we render `+N more`
  and the rest are findable via `useSkill` (which currently
  requires knowing the name). On a corpus of >20 user skills
  there's no graceful discovery for the tail. Acceptable while
  total skill counts are small; revisit if anyone hits the cap.
- **No skill body validation.** A user could `teachSkill` a 50KB
  prose dump that, when invoked, blows the context window. The
  catalogue protects the system prompt (description only); the
  body only enters the prompt when Perry calls `useSkill`. Any
  blow-up is opt-in.
- **No deletion tool.** `forgetById` against the
  `user:<uid>:skills` shelf works as an escape hatch but isn't
  exposed as a first-class skill operation. Add one if user-skill
  churn becomes real.
- **Skills are scoped to one user.** No team / shared library. Fine
  for the current single-user deployment.

## Known limitations / follow-ups

- No `findSkill(query)` for the >20 case (catalogue truncates).
- No first-class `forgetSkill` tool.
- `slug()` is intentionally lossy (lower-kebab, ASCII only); a
  Unicode skill name will be silently mangled.
- Built-in skills only re-load on app restart — fine for shipped
  playbooks, but a hot-reload would be nicer in dev.

## Operational notes

- Add a built-in by dropping `src/main/resources/skills/<slug>/SKILL.md`
  with valid frontmatter; it loads on next boot. Log line on
  startup: `SkillRegistry loaded built-in skills: [...]`.
- User skills live on shelf `user:<uid>:skills` and show up in
  `--list-shelves` and `searchMemory` like any other shelf.

## References

- Sibling: [`memory-briefing.md`](memory-briefing.md) — the same
  pattern of "always-on context block in the system prompt."
- Sibling: [`scheduling.md`](scheduling.md) — the cron + note
  mechanics that the `batch-cleanup` built-in leans on.
- Tests:
  [`SkillRegistryTest.java`](../../src/test/java/brainjar/skill/SkillRegistryTest.java),
  [`SkillToolTest.java`](../../src/test/java/brainjar/skill/SkillToolTest.java),
  [`AiConfigSkillsBlockTest.java`](../../src/test/java/brainjar/discord/ai/AiConfigSkillsBlockTest.java).
