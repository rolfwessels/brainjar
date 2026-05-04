# Vision: Personal Agent (Second Brain)

## Purpose
A Discord-based personal agent that captures information via voice/chat, stores it in a structured way, and helps recall, organize, and suggest actions.

## Core Principles
- Capture friction must be near zero (voice-first).
- Storage is generic and extensible (no rigid schemas).
- Retrieval should feel conversational.
- System evolves through iteration, not upfront design.

## Interaction Model
- User speaks or types in Discord.
- Agent interprets intent (e.g. "add movie", "mark watched").
- Agent stores or retrieves information.
- Agent can proactively suggest (e.g. weekly summaries).

## Key Technologies
- Discord Bot (interaction layer)
- LangChain (LLM orchestration)
- LangGraph (stateful workflows / agent logic)
- LangSmith (observability / debugging)
- Java ecosystem (primary implementation)

## Storage Approach (POC)
Use a **generic graph-based model**:

### Node Types
- `Topic` (e.g. Movies, Books, Travel)
- `Item` (e.g. "Dune", "Breaking Bad")
- `Tag` (e.g. Sci-Fi, Recommended, Watched)

### Edge Types
- `BELONGS_TO` (Item → Topic)
- `TAGGED_AS` (Item → Tag)
- `RELATED_TO` (Item ↔ Item or Topic ↔ Topic)

### Properties (examples)
- Item:
  - `name`
  - `type` (movie, book, etc.)
  - `status` (planned, in-progress, completed)
  - `source` (who recommended it)
  - `created_at`

## Storage Options (to explore)
- Graph DB (Neo4j) → natural fit
- Document DB (MongoDB) → flexible, easier infra
- Hybrid (Mongo + embeddings for semantic search)

## POC Scenarios

### 1. Capture Recommendation
User: "Someone recommended Dune"
→ Agent:
- Infers: Item = Dune, Topic = Movie (or unknown → inferred later)
- Stores node + relationships

### 2. Update Status
User: "I watched Dune"
→ Agent:
- Finds item
- Updates status = completed
- Optionally tags as `Watched`

### 3. Retrieve Suggestions
User: "What should I watch?"
→ Agent:
- Queries items with status = planned
- Filters by tags or recency
- Returns suggestions

### 4. Weekly Summary (optional)
- List newly added items
- Highlight untouched recommendations
- Suggest next actions

## First Milestones (POC)
1. Discord bot (text first, voice later)
2. Basic intent parsing (LangChain)
3. Simple graph model (in-memory or Mongo)
4. Add + query items
5. Iterate on schema + flows

## Future Ideas
- Voice-first capture (speech-to-text pipeline)
- Auto-classification (movie vs book vs other)
- Travel spreadsheet sync
- Personal insights (patterns, preferences)
- Cross-linking ideas beyond media (true "second brain")