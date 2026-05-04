# Chunking

Recall splits text into chunks with a default size of 800 characters and 100
characters of overlap. Paragraph and line boundaries are preferred when the
chunk window allows it. Each chunk becomes a Page with a stable id derived
from the source path and chunk index.
