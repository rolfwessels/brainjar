package brainjar.recall.store;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryPageStore implements PageStore {

    private final EmbeddingModel embeddingModel;
    private InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final Map<String, Page> pageIndex = new HashMap<>();

    public InMemoryPageStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = new InMemoryEmbeddingStore<>();
    }

    @Override
    public void store(List<Page> pages) {
        var ids = new ArrayList<String>();
        var embeddings = new ArrayList<Embedding>();
        var segments = new ArrayList<TextSegment>();

        for (var page : pages) {
            if (pageIndex.containsKey(page.id())) {
                deletePageFromStore(page.id());
            }
            var segment = TextSegment.from(page.content(), buildMetadata(page));
            ids.add(page.id());
            embeddings.add(embeddingModel.embed(segment).content());
            segments.add(segment);
            pageIndex.put(page.id(), page);
        }

        embeddingStore.addAll(ids, embeddings, segments);
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        return executeSearch(query, maxResults, null);
    }

    @Override
    public List<SearchResult> search(String query, int maxResults, String shelfName) {
        return executeSearch(query, maxResults, shelfName);
    }

    @Override
    public void deleteByBook(Book book) {
        var sourcePath = book.sourcePath().toString();
        var toRemove = pageIndex.values().stream()
                .filter(page -> page.book().sourcePath().toString().equals(sourcePath))
                .map(Page::id)
                .toList();

        for (var id : toRemove) {
            deletePageFromStore(id);
        }
    }

    @Override
    public int deleteByShelf(String shelfName) {
        var toRemove = pageIndex.values().stream()
                .filter(page -> page.book().shelf().name().equals(shelfName))
                .map(Page::id)
                .toList();

        for (var id : toRemove) {
            deletePageFromStore(id);
        }
        return toRemove.size();
    }

    @Override
    public void deletePage(String pageId) {
        if (!pageIndex.containsKey(pageId)) {
            return;
        }
        deletePageFromStore(pageId);
    }

    @Override
    public Optional<Page> findById(String pageId) {
        return Optional.ofNullable(pageIndex.get(pageId));
    }

    @Override
    public int nextChunkIndex(Book book) {
        var sourcePath = book.sourcePath().toString();
        return pageIndex.values().stream()
                .filter(page -> page.book().sourcePath().toString().equals(sourcePath))
                .mapToInt(Page::chunkIndex)
                .max()
                .orElse(-1) + 1;
    }

    @Override
    public List<Page> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return pageIndex.values().stream()
                .sorted(InMemoryPageStore::byMostRecent)
                .limit(limit)
                .toList();
    }

    @Override
    public List<Page> recentByShelf(String shelfName, int limit) {
        if (limit <= 0 || shelfName == null) {
            return List.of();
        }
        return pageIndex.values().stream()
                .filter(p -> shelfName.equals(p.book().shelf().name()))
                .sorted(InMemoryPageStore::byMostRecent)
                .limit(limit)
                .toList();
    }

    private static int byMostRecent(Page a, Page b) {
        var aTime = a.book().lastModified();
        var bTime = b.book().lastModified();
        if (aTime == null && bTime == null) return 0;
        if (aTime == null) return 1;
        if (bTime == null) return -1;
        return bTime.compareTo(aTime);
    }

    @Override
    public int size() {
        return pageIndex.size();
    }

    void serializeToFile(Path path) throws IOException {
        Files.writeString(path, embeddingStore.serializeToJson());
    }

    void restoreFromFile(Path path) {
        embeddingStore = InMemoryEmbeddingStore.fromFile(path);
        rebuildPageIndex();
    }

    private void rebuildPageIndex() {
        pageIndex.clear();
        var allResults = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModel.embed("rebuild index query").content())
                        .maxResults(Integer.MAX_VALUE)
                        .minScore(0.0)
                        .build()
        ).matches();

        for (var match : allResults) {
            var segment = match.embedded();
            if (segment == null) {
                continue;
            }
            var meta = segment.metadata();
            var pageId = meta.getString("pageId");
            var shelf = new Shelf(meta.getString("shelf"));
            var sourcePath = Path.of(meta.getString("sourcePath"));
            var chunkIndex = meta.getInteger("chunkIndex");
            var title = meta.getString("title");
            var lastModified = meta.getString("lastModified");

            var book = new Book(sourcePath, title != null ? title : sourcePath.getFileName().toString(),
                    shelf, lastModified != null ? Instant.parse(lastModified) : Instant.now());
            var page = new Page(pageId, segment.text(), chunkIndex, book);
            pageIndex.put(pageId, page);
        }
    }

    private List<SearchResult> executeSearch(String query, int maxResults, String shelfName) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        var requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults);
        if (shelfName != null) {
            requestBuilder.filter(MetadataFilterBuilder.metadataKey("shelf").isEqualTo(shelfName));
        }

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(requestBuilder.build()).matches();

        var results = new ArrayList<SearchResult>();
        for (var match : matches) {
            var page = pageIndex.get(match.embeddingId());
            if (page == null) {
                continue;
            }
            results.add(new SearchResult(page, match.score()));
            if (results.size() >= maxResults) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private dev.langchain4j.data.document.Metadata buildMetadata(Page page) {
        return new dev.langchain4j.data.document.Metadata()
                .put("pageId", page.id())
                .put("shelf", page.book().shelf().name())
                .put("sourcePath", page.book().sourcePath().toString())
                .put("title", page.book().title())
                .put("lastModified", page.book().lastModified() != null ? page.book().lastModified().toString() : null)
                .put("chunkIndex", page.chunkIndex());
    }

    private void deletePageFromStore(String pageId) {
        embeddingStore.remove(pageId);
        pageIndex.remove(pageId);
    }
}
