# BrainJar

A Discord-based personal agent — your second brain. Captures information via chat (voice coming later), stores it in a structured way, and helps you recall, organise, and act on it.

See [docs/vision.md](docs/vision.md) for the full vision.

## Stack

- Java 21 + Spring Boot 3.4.4
- [JDA 6](https://github.com/discord-jda/JDA) (Discord bot)
- Gradle 8 (Kotlin DSL)
- Docker / Make workflow

## Discord Bot Setup

### 1. Create a Discord Application

1. Go to [https://discord.com/developers/applications](https://discord.com/developers/applications)
2. Click **New Application**, give it a name, click **Create**

### 2. Get Your Bot Token

1. In the left sidebar, click **Bot**
2. Under **Token**, click **Reset Token** and copy it — save it somewhere safe, you won't see it again
3. Scroll down to **Privileged Gateway Intents** and enable **Message Content Intent**

### 3. Configure Install Settings (this is where the permissions live now)

The old OAuth2 URL Generator is largely replaced. Discord now uses the **Installation** page:

1. In the left sidebar, click **Installation**
2. Under **Installation Contexts**, make sure **Guild Install** is checked
3. Under **Default Install Settings → Guild Install**, click the **Scopes** dropdown and add:
   - `bot`
   - `applications.commands`
4. Once `bot` is selected, a **Permissions** dropdown appears — add: (2147551232)
   - `Send Messages`
   - `Read Message History`
   - `Use Slash Commands`
5. Copy the **Install Link** shown at the top of that same page

### 4. Add the Bot to Your Server

1. Paste the Install Link into your browser
2. Select your server from the dropdown and click **Authorise**
3. The bot should now appear in your server's member list (offline until you run it)

### 5. Get Your Server ID

1. In Discord, go to **User Settings → Advanced** and enable **Developer Mode**
2. Right-click your server icon in the sidebar → **Copy Server ID**

### 6. Configure Environment Variables

Copy `sample.env` to `.env` and fill in your values:

```bash
cp sample.env .env
```

```env
DISCORD_BOT_TOKEN=your-bot-token-here
DISCORD_GUILD_ID=your-server-id-here   # optional but recommended for dev (instant slash command registration)
OPENAI_API_KEY=your-openai-api-key-here
BRAVE_API_KEY=your-brave-api-key-here
```

> Tip: Without `DISCORD_GUILD_ID`, slash commands register globally and can take up to an hour to appear. With a guild ID, they appear instantly — useful while developing.

### 7. Get an OpenAI API Key

1. Go to [https://platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. Click **Create new secret key**, copy it
3. Make sure your account has credits (usage-based billing)

### 8. Get a Brave Search API Key

Perry uses the [Brave Search API](https://brave.com/search/api/) to look up real-time information when needed.

1. Go to [https://api.search.brave.com/app/keys](https://api.search.brave.com/app/keys) (sign in with a Brave account)
2. Click **New Key**, give it a name, copy the key
3. The **Free tier** includes 2,000 queries/month — enough for personal use

> The key goes in `.env` as `BRAVE_API_KEY`.

## Development

### Run inside Docker (recommended)

```bash
make up       # start container and attach shell
make start    # run the bot (inside container)
make test     # run tests (inside container)
make down     # stop container
```

### Run locally

```bash
export DISCORD_BOT_TOKEN=your-token
export DISCORD_GUILD_ID=your-guild-id   # optional
./gradlew bootRun
```

### Recall CLI

Mine, search, and manage Perry's long-term memory:

```bash
./brainjar --mine /path/to/docs --shelf docs
./brainjar --search "how does chunking work"
./brainjar --remove-shelf docs
./brainjar --briefing
./brainjar --list-shelves
./brainjar --latest                      # 10 most recent pages, all shelves
./brainjar --latest -n 5 --shelf movies  # 5 most recent pages on one shelf
./brainjar --list-jobs                   # all scheduled one-shot/cron jobs
./brainjar --export-kg                   # dump the knowledge graph for external viz
./brainjar --export-md                   # export all books as markdown files
./brainjar --export-md --shelf movies    # export one shelf only
./brainjar --export-md /path/to/out      # override output directory
```

`--list-shelves` enumerates every shelf with its page count, sorted
by size. `--latest` shows the most recently captured pages (defaults
to 10, override with `-n` or `--max`, optionally filter with
`--shelf`). `--list-jobs` dumps all scheduled jobs across users,
including their kind (`ONCE` / `CRON`), fire time or cron expression,
note, and prompt preview — handy for spotting stuck or duplicate
schedules.

`--export-kg` dumps the temporal knowledge graph to disk so you can
explore it in Neo4j Desktop, Gephi, or any graph tool. Defaults write
to `~/.recall/export/`:

- `--format cypher` (default) → `kg.cypher`, a self-contained script
  using `MERGE` + `UNWIND`. Paste into Neo4j Browser, `:source` it, or
  pipe through `cypher-shell`. Predicates become real relationship
  types (`WORKS_AT`, `KNOWS`, …) so edge labels render cleanly.
- `--format csv` → `nodes.csv` + `edges.csv` with `source`/`target`
  columns. Suits `LOAD CSV WITH HEADERS` in Neo4j, or drop into Gephi's
  spreadsheet importer.
- `--format tsv` → same layout, tab-separated, with backslash-escaping
  for tabs/newlines.

Pass a path (file for cypher, directory for csv/tsv) to override the
default location. The export is idempotent — re-running against the
same Neo4j database updates properties in place.

`--export-md` writes one markdown file per Book to `~/.recall/export/<shelf>/<date>-<title>.md`.
Each file contains the Book's Pages concatenated in order, making the export human-readable and
diff-friendly. Use `--shelf` to limit to one shelf, or pass an output directory to override the
default location.

`--briefing` prints the compact "what's in memory" block that gets
injected into Perry's system prompt on every turn — shelf inventory
plus a few recent highlights, capped at ~150 tokens. Useful for
sanity-checking what Perry actually sees before replying:

```text
## Memory briefing
Shelves: docs (93), notes (4), tech (2), profile (1)
Recent:
- [tech] Uses HotChocolate for GraphQL in C#.
- [profile] User's org is Circulor.

Store: 100 pages indexed.
Briefing length: 284 chars (~71 tokens).
```

See [docs/features/recall.md](docs/features/recall.md) for the full
feature overview and [docs/features/memory-briefing.md](docs/features/memory-briefing.md)
for the briefing design.

### Run tests

```bash
./gradlew test
```

## Project Structure

```
src/main/java/brainjar/
  BrainJarApplication.java
  discord/
    DiscordBotConfig.java         -- JDA bean wiring
    DiscordProperties.java        -- @ConfigurationProperties record
    listener/
      ReadyListener.java          -- startup + slash command registration
      DirectMessageListener.java  -- handles DMs, delegates to AI
      SlashCommandListener.java   -- routes slash commands to handlers
    command/
      SlashCommand.java           -- interface for slash commands
      PingCommand.java            -- /ping: health check
      ClearSessionCommand.java    -- /clear-session: drop the rolling chat window
    ai/
      BrainJarAssistant.java      -- @AiService: Perry's persona + tool wiring
      AiConfig.java               -- ChatMemoryProvider (per-user history)
      ChatMemoryRegistry.java     -- owns per-user ChatMemory so /clear-session can evict
      BraveProperties.java        -- @ConfigurationProperties for Brave API key
      BraveSearchTool.java        -- @Tool: live web search via Brave Search API
  recall/
    model/                        -- Shelf, Book, Page, Summary (records)
    ingest/                       -- Chunker, Miner, SummaryCompressor
    search/                       -- Searcher, LayeredContext (L0-L3)
    kg/                           -- KnowledgeGraph, Triple, Entity
    store/                        -- PageStore, InMemoryPageStore, FilePageStore
    RecallConfig.java             -- Spring @Configuration for recall beans
    RecallTool.java               -- @Tool methods: searchMemory, recall
    RecallCommand.java            -- CLI: --mine, --search, --remove-shelf, --briefing
```

## References

- [Discord Developer Portal](https://discord.com/developers/applications)
- [JDA Documentation](https://docs.jda.wiki/)
- [Discord Bot Java Research](docs/discord-java.md)
