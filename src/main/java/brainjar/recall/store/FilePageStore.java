package brainjar.recall.store;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class FilePageStore implements PageStore {

    private final EmbeddingModel embeddingModel;
    private final Path embeddingsFile;
    private InMemoryPageStore delegate;

    public FilePageStore(EmbeddingModel embeddingModel, Path embeddingsFile) {
        this.embeddingModel = embeddingModel;
        this.embeddingsFile = embeddingsFile;
        this.delegate = new InMemoryPageStore(embeddingModel);
        loadIfExists();
    }

    @Override
    public void store(List<Page> pages) {
        delegate.store(pages);
        save();
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        return delegate.search(query, maxResults);
    }

    @Override
    public List<SearchResult> search(String query, int maxResults, String shelfName) {
        return delegate.search(query, maxResults, shelfName);
    }

    @Override
    public void deleteByBook(Book book) {
        delegate.deleteByBook(book);
        save();
    }

    @Override
    public int deleteByShelf(String shelfName) {
        int removed = delegate.deleteByShelf(shelfName);
        save();
        return removed;
    }

    @Override
    public void deletePage(String pageId) {
        delegate.deletePage(pageId);
        save();
    }

    @Override
    public Optional<Page> findById(String pageId) {
        return delegate.findById(pageId);
    }

    @Override
    public int nextChunkIndex(Book book) {
        return delegate.nextChunkIndex(book);
    }

    @Override
    public List<Page> recent(int limit) {
        return delegate.recent(limit);
    }

    @Override
    public List<Page> recentByShelf(String shelfName, int limit) {
        return delegate.recentByShelf(shelfName, limit);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    private void save() {
        try {
            var parent = embeddingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            delegate.serializeToFile(embeddingsFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save page store", e);
        }
    }

    private void loadIfExists() {
        if (Files.exists(embeddingsFile)) {
            delegate.restoreFromFile(embeddingsFile);
        }
    }
}
