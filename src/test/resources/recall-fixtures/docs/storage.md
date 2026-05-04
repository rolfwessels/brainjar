# Storage

Recall persists the embedding index to a JSON file on disk at
`~/.recall/embeddings.json`. The knowledge graph is a SQLite database at
`~/.recall/knowledge_graph.sqlite3`. Both files live under the same palace
directory so a single directory deletion resets the entire memory.
