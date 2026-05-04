package brainjar.recall.search;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import brainjar.recall.store.SearchResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearcherTest {

    private static final Shelf SHELF = new Shelf("docs");

    @Test
    void search_ShouldCombineCosineAndKeywordRankings() {
        var store = new InMemoryPageStore(new FakeEmbeddingModel());
        var bookA = new Book(Path.of("/t/a.md"), "a.md", SHELF, Instant.now());
        var bookB = new Book(Path.of("/t/b.md"), "b.md", SHELF, Instant.now());
        var bookC = new Book(Path.of("/t/c.md"), "c.md", SHELF, Instant.now());
        store.store(List.of(
                new Page(Page.generateId(bookA.sourcePath().toString(), 0),
                        "Documentation about BRAVE_API_KEY and how to configure it in the environment.", 0, bookA),
                new Page(Page.generateId(bookB.sourcePath().toString(), 0),
                        "General note about environment variables for API authentication in Spring.", 0, bookB),
                new Page(Page.generateId(bookC.sourcePath().toString(), 0),
                        "Unrelated note about database connection pool tuning and metrics.", 0, bookC)
        ));
        var hybrid = new HybridSearcher(store, new KeywordIndex(store));

        var results = hybrid.search("BRAVE_API_KEY configuration", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().page().book().title()).isEqualTo("a.md");
    }

    @Test
    void fuse_ShouldGiveHigherScoreToDocumentsAppearingInBothLists() {
        var page1 = page("p1", "content1");
        var page2 = page("p2", "content2");
        var page3 = page("p3", "content3");

        var cosine = List.of(
                new SearchResult(page1, 0.9),
                new SearchResult(page2, 0.8)
        );
        var keyword = List.of(
                new SearchResult(page2, 5.0),
                new SearchResult(page3, 3.0)
        );

        var fused = HybridSearcher.fuse(cosine, keyword, 3);

        assertThat(fused).hasSize(3);
        assertThat(fused.getFirst().page().id()).isEqualTo("p2");
    }

    @Test
    void fuse_ShouldRespectMaxResults() {
        var cosine = List.of(
                new SearchResult(page("p1", "a"), 0.9),
                new SearchResult(page("p2", "b"), 0.8),
                new SearchResult(page("p3", "c"), 0.7),
                new SearchResult(page("p4", "d"), 0.6)
        );

        var fused = HybridSearcher.fuse(cosine, List.of(), 2);

        assertThat(fused).hasSize(2);
    }

    private static Page page(String id, String content) {
        var book = new Book(Path.of("/t/" + id + ".md"), id + ".md", SHELF, Instant.now());
        return new Page(id, content, 0, book);
    }
}
