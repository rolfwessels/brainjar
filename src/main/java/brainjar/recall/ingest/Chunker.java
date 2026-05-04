package brainjar.recall.ingest;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;

import java.util.ArrayList;
import java.util.List;

public class Chunker {

    static final int DEFAULT_CHUNK_SIZE = 800;
    static final int DEFAULT_OVERLAP = 100;
    static final int MIN_CHUNK_SIZE = 50;

    private final int chunkSize;
    private final int overlap;

    public Chunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public Chunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<Page> chunk(String content, Book book) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        var pages = new ArrayList<Page>();
        var sourcePath = book.sourcePath().toString();
        int start = 0;

        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            if (end < content.length()) {
                end = findBreakPoint(content, start, end);
            }

            var chunk = content.substring(start, end).strip();
            if (chunk.length() >= MIN_CHUNK_SIZE) {
                int index = pages.size();
                var id = Page.generateId(sourcePath, index);
                pages.add(new Page(id, chunk, index, book));
            } else if (!chunk.isEmpty() && !pages.isEmpty()) {
                appendToPreviousPage(pages, chunk, sourcePath);
            }

            start = (end >= content.length()) ? end : end - overlap;
        }

        return List.copyOf(pages);
    }

    private int findBreakPoint(String content, int start, int end) {
        int midpoint = start + chunkSize / 2;

        int paragraphBreak = content.lastIndexOf("\n\n", end);
        if (paragraphBreak > midpoint) {
            return paragraphBreak + 2;
        }

        int lineBreak = content.lastIndexOf('\n', end);
        if (lineBreak > midpoint) {
            return lineBreak + 1;
        }

        return end;
    }

    private void appendToPreviousPage(List<Page> pages, String chunk, String sourcePath) {
        var previous = pages.removeLast();
        var merged = previous.content() + "\n" + chunk;
        int index = previous.chunkIndex();
        var id = Page.generateId(sourcePath, index);
        pages.add(new Page(id, merged, index, previous.book()));
    }
}
