package brainjar.recall.search;

import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearcherTest {

    private Searcher searcher;
    private InMemoryPageStore store;
    private KnowledgeGraph kg;

    private static final Shelf DOCS_SHELF = new Shelf("docs");

    private static Book createBook(String filename) {
        return new Book(Path.of("/test/" + filename), filename, DOCS_SHELF, Instant.now());
    }

    private static Page createPage(String content, int chunkIndex, Book book) {
        var id = Page.generateId(book.sourcePath().toString(), chunkIndex);
        return new Page(id, content, chunkIndex, book);
    }

    private void setup() {
        store = new InMemoryPageStore(new FakeEmbeddingModel());
        kg = new KnowledgeGraph("jdbc:sqlite::memory:");
        searcher = new Searcher(store, kg);
    }

    @Test
    void search_ShouldReturnRankedResults() {
        // arrange
        setup();
        var book = createBook("guide.md");
        store.store(List.of(
                createPage("Java is a strongly typed programming language for enterprise applications", 0, book),
                createPage("Python is popular for machine learning and data science workflows", 1, book)
        ));

        // act
        var results = searcher.search("Java programming", 5);

        // assert
        assertThat(results).isNotEmpty();
    }

    @Test
    void search_WithShelf_ShouldFilterResults() {
        // arrange
        setup();
        var docsBook = createBook("guide.md");
        var codeShelf = new Shelf("code");
        var codeBook = new Book(Path.of("/test/App.java"), "App.java", codeShelf, Instant.now());
        store.store(List.of(
                createPage("Spring Boot documentation and configuration guide for developers", 0, docsBook),
                createPage("public static void main method entry point for the application class", 0, codeBook)
        ));

        // act
        var results = searcher.search("Spring Boot", 5, "docs");

        // assert
        assertThat(results).allMatch(r -> r.page().book().shelf().name().equals("docs"));
    }

    @Test
    void search_WhenEmpty_ShouldReturnEmptyList() {
        // arrange
        setup();

        // act
        var results = searcher.search("anything", 5);

        // assert
        assertThat(results).isEmpty();
    }

    @Test
    void findRelatedFacts_ShouldReturnKnowledgeGraphTriples() {
        // arrange
        setup();
        kg.addTriple("Perry", "uses", "OpenAI", LocalDate.of(2026, 1, 1), 1.0, null);

        // act
        var facts = searcher.findRelatedFacts("Perry");

        // assert
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().predicate()).isEqualTo("uses");
    }

    @Test
    void findRelatedFacts_WithDate_ShouldFilterByValidity() {
        // arrange
        setup();
        kg.addTriple("Perry", "uses", "GPT-4", LocalDate.of(2025, 1, 1), 1.0, null);
        kg.invalidate("Perry", "uses", "GPT-4", LocalDate.of(2025, 12, 31));
        kg.addTriple("Perry", "uses", "GPT-5", LocalDate.of(2026, 1, 1), 1.0, null);

        // act
        var facts = searcher.findRelatedFacts("Perry", LocalDate.of(2026, 6, 1));

        // assert
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().object()).isEqualTo("gpt_5");
    }

    @Test
    void findRelatedFacts_WhenNoKG_ShouldReturnEmpty() {
        // arrange
        store = new InMemoryPageStore(new FakeEmbeddingModel());
        searcher = new Searcher(store);

        // act
        var facts = searcher.findRelatedFacts("Perry");

        // assert
        assertThat(facts).isEmpty();
    }
}
