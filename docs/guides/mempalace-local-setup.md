# MemPalace Local Setup

How to install MemPalace and mine BrainJar project files into a searchable palace.

## Prerequisites

- Python 3.10+
- pip (if missing: `curl -sSL https://bootstrap.pypa.io/get-pip.py -o /tmp/get-pip.py && python3 /tmp/get-pip.py --user`)

## Install

```bash
pip install mempalace
```

Installs to `~/.local/bin/`. Ensure it's on your PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

## Initialize the Palace

Scans the project directory, detects entities (people/projects) and creates a room structure based on folder layout.

```bash
mempalace init --yes /home/rolf/work/home/BrainJar
```

- `--yes` skips interactive prompts (auto-accepts detected entities)
- Creates `mempalace.yaml` and `entities.json` in the project root (both gitignored)
- Palace data lives in `~/.mempalace/palace`

This produces a wing called `brainjar` with rooms like `documentation`, `src`, `gradle`, `testing`, etc.

## Mine Project Files

Reads all files, chunks them (800 chars / 100 overlap), and stores embeddings in ChromaDB.

```bash
mempalace mine /home/rolf/work/home/BrainJar
```

- Respects `.gitignore` by default
- First run downloads the embedding model (~80MB, cached at `~/.cache/chroma/`)
- Re-running skips already-filed files (idempotent)

### Re-mine After Changes

Just run `mine` again — it only processes new/changed files:

```bash
mempalace mine /home/rolf/work/home/BrainJar
```

### Mine Conversation Exports

If you have chat transcripts (Claude, ChatGPT, Slack exports):

```bash
# Optional: split mega-files into per-session files first
mempalace split ~/chats/ --dry-run
mempalace split ~/chats/

# Then mine in conversation mode
mempalace mine ~/chats/ --mode convos
```

`split` is only needed for concatenated transcript files containing multiple sessions. Not used for project files.

## Search

```bash
# Broad search
mempalace search "how does Perry handle messages"

# Scoped to a wing and room (more precise)
mempalace search "Brave search implementation" --wing brainjar --room src
```

## Other Useful Commands

```bash
# Palace overview (wings, rooms, drawer counts)
mempalace status

# Wake-up context (~800 tokens summary for LLM priming)
mempalace wake-up --wing brainjar

# Show MCP server setup command (for Cursor/Claude integration)
mempalace mcp
```

## File Locations

| What | Where |
|---|---|
| Palace data (ChromaDB + KG) | `~/.mempalace/palace` |
| Embedding model cache | `~/.cache/chroma/onnx_models/` |
| Project config | `./mempalace.yaml` (gitignored) |
| Detected entities | `./entities.json` (gitignored) |
| Identity file (optional) | `~/.mempalace/identity.txt` |
