package brainjar.recall;

import brainjar.recall.ingest.Chunker;
import brainjar.recall.ingest.Miner;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration test promised in
 * {@code docs/research/retrieval-comparison-mempalace-vs-recall.md} §
 * "Automation next step". Uses a small fixture corpus under
 * {@code src/test/resources/recall-fixtures} plus {@link FakeEmbeddingModel}
 * so the test is deterministic and free of ONNX startup cost.
 *
 * <p>The fixture corpus is intentionally tiny; assertions use substring /
 * top-k membership rather than exact ranking, so the test is robust to
 * embedding-model noise (it just has to rank the right file above the wrong ones).
 */
class RetrievalIntegrationTest {

    private PageStore pageStore;
    private Miner miner;

    @BeforeEach
    void setUp() {
        pageStore = new InMemoryPageStore(new FakeEmbeddingModel());
        miner = new Miner(new Chunker(), pageStore);
        miner.mineDirectory(fixturesRoot(), new Shelf("docs"));
    }

    @Test
    void corpus_ShouldBeIndexed() {
        assertThat(pageStore.size()).isGreaterThan(0);
    }

    @Test
    void chunkingQuery_ShouldSurfaceChunkingDoc() {
        var results = pageStore.search("chunk size and overlap", 3);

        assertThat(sourceFiles(results)).contains("chunking.md");
    }

    @Test
    void embeddingsPathQuery_ShouldSurfaceStorageDoc() {
        var results = pageStore.search("embeddings file on disk", 3);

        assertThat(sourceFiles(results)).contains("storage.md");
    }

    @Test
    void firstMilestoneQuery_ShouldSurfaceVisionDoc() {
        var results = pageStore.search("first milestone proof of concept Discord", 3);

        assertThat(sourceFiles(results)).contains("vision.md");
    }

    @Test
    void braveApiKeyQuery_ShouldSurfaceBraveDoc() {
        var results = pageStore.search("BRAVE_API_KEY environment variable", 3);

        assertThat(sourceFiles(results)).contains("brave.md");
    }

    @Test
    void shelfFilter_ShouldOnlyReturnMatchingShelf() {
        var results = pageStore.search("anything", 10, "other-shelf");

        assertThat(results).isEmpty();
    }

    @Test
    void recent_ShouldReturnNewestFirst() {
        var recent = pageStore.recent(10);

        assertThat(recent).isNotEmpty();
        for (int i = 1; i < recent.size(); i++) {
            var prev = recent.get(i - 1).book().lastModified();
            var curr = recent.get(i).book().lastModified();
            if (prev != null && curr != null) {
                assertThat(prev).isAfterOrEqualTo(curr);
            }
        }
    }

    private static List<String> sourceFiles(List<SearchResult> results) {
        return results.stream()
                .map(r -> r.page().book().sourcePath().getFileName().toString())
                .toList();
    }

    private static Path fixturesRoot() {
        try {
            var url = Objects.requireNonNull(
                    RetrievalIntegrationTest.class.getClassLoader().getResource("recall-fixtures/docs"),
                    "recall-fixtures/docs not found on classpath");
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to resolve fixtures path", e);
        }
    }
}
