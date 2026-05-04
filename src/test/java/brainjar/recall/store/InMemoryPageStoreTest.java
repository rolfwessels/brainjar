package brainjar.recall.store;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPageStoreTest {

    private InMemoryPageStore store;

    private static final Shelf DOCS_SHELF = new Shelf("docs");
    private static final Shelf CODE_SHELF = new Shelf("code");

    private static Book createBook(String filename, Shelf shelf) {
        return new Book(Path.of("/test/" + filename), filename, shelf, Instant.now());
    }

    private static Page createPage(String content, int chunkIndex, Book book) {
        var id = Page.generateId(book.sourcePath().toString(), chunkIndex);
        return new Page(id, content, chunkIndex, book);
    }

    private void setup() {
        store = new InMemoryPageStore(new FakeEmbeddingModel());
    }

    @Test
    void search_WhenStoreIsEmpty_ShouldReturnEmptyList() {
        // arrange
        setup();

        // act
        var results = store.search("anything", 5);

        // assert
        assertThat(results).isEmpty();
    }

    @Test
    void store_AndSearch_ShouldReturnRelevantResults() {
        // arrange
        setup();
        var book = createBook("java-guide.md", DOCS_SHELF);
        var pages = List.of(
                createPage("Java is a strongly typed programming language used for building enterprise applications", 0, book),
                createPage("Python is a dynamically typed language popular for data science and machine learning", 1, book)
        );
        store.store(pages);

        // act
        var results = store.search("Java enterprise development", 5);

        // assert
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().page().content()).contains("Java");
    }

    @Test
    void search_WithShelfFilter_ShouldOnlyReturnPagesFromThatShelf() {
        // arrange
        setup();
        var docsBook = createBook("guide.md", DOCS_SHELF);
        var codeBook = createBook("App.java", CODE_SHELF);
        store.store(List.of(
                createPage("Spring Boot is a Java framework for building web applications quickly", 0, docsBook),
                createPage("public class Application implements CommandLineRunner", 0, codeBook)
        ));

        // act
        var results = store.search("Spring Boot application", 5, "docs");

        // assert
        assertThat(results).allMatch(r -> r.page().book().shelf().name().equals("docs"));
    }

    @Test
    void deleteByBook_ShouldRemoveOnlyThatBooksPages() {
        // arrange
        setup();
        var book1 = createBook("first.md", DOCS_SHELF);
        var book2 = createBook("second.md", DOCS_SHELF);
        store.store(List.of(createPage("Content from the first document about testing", 0, book1)));
        store.store(List.of(createPage("Content from the second document about deployment", 0, book2)));
        assertThat(store.size()).isEqualTo(2);

        // act
        store.deleteByBook(book1);

        // assert
        assertThat(store.size()).isEqualTo(1);
        var results = store.search("deployment", 5);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().page().book().title()).isEqualTo("second.md");
    }

    @Test
    void store_WhenDuplicatePageId_ShouldOverwrite() {
        // arrange
        setup();
        var book = createBook("doc.md", DOCS_SHELF);
        var original = createPage("Original content about databases and storage systems", 0, book);
        var updated = createPage("Updated content about message queues and event systems", 0, book);
        store.store(List.of(original));

        // act
        store.store(List.of(updated));

        // assert
        assertThat(store.size()).isEqualTo(1);
        var results = store.search("message queues events", 5);
        assertThat(results.getFirst().page().content()).contains("Updated");
    }

    @Test
    void search_ShouldReturnScores() {
        // arrange
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Discord bots can be built using JDA, a Java library for the Discord API", 0, book)
        ));

        // act
        var results = store.search("Discord bot Java", 5);

        // assert
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().score()).isGreaterThan(0.0);
    }

    @Test
    void deletePage_ShouldRemoveOnlyThatPage() {
        // arrange
        setup();
        var book = createBook("doc.md", DOCS_SHELF);
        var first = createPage("First page about architecture patterns", 0, book);
        var second = createPage("Second page about deployment strategies", 1, book);
        store.store(List.of(first, second));

        // act
        store.deletePage(first.id());

        // assert
        assertThat(store.size()).isEqualTo(1);
        var results = store.search("architecture", 5);
        assertThat(results).allMatch(r -> !r.page().id().equals(first.id()));
    }

    @Test
    void deletePage_WhenUnknownId_ShouldBeNoOp() {
        // arrange
        setup();
        var book = createBook("doc.md", DOCS_SHELF);
        store.store(List.of(createPage("Page about testing frameworks", 0, book)));

        // act
        store.deletePage("does-not-exist");

        // assert
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void nextChunkIndex_WhenBookHasNoPages_ShouldBeZero() {
        // arrange
        setup();
        var book = createBook("fresh.md", DOCS_SHELF);

        // act
        int next = store.nextChunkIndex(book);

        // assert
        assertThat(next).isZero();
    }

    @Test
    void nextChunkIndex_WhenBookHasPages_ShouldBeMaxPlusOne() {
        // arrange
        setup();
        var book = createBook("captures.md", DOCS_SHELF);
        store.store(List.of(
                createPage("First captured memory about system design patterns", 0, book),
                createPage("Second captured memory about deployment strategies used", 1, book),
                createPage("Third captured memory about observability and monitoring", 2, book)
        ));

        // act
        int next = store.nextChunkIndex(book);

        // assert
        assertThat(next).isEqualTo(3);
    }

    @Test
    void nextChunkIndex_ShouldBeScopedPerBook() {
        // arrange
        setup();
        var bookA = createBook("a.md", DOCS_SHELF);
        var bookB = createBook("b.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Book A first chunk about architecture decisions taken", 0, bookA),
                createPage("Book A second chunk about infrastructure and deployment", 1, bookA)
        ));

        // act
        int nextA = store.nextChunkIndex(bookA);
        int nextB = store.nextChunkIndex(bookB);

        // assert
        assertThat(nextA).isEqualTo(2);
        assertThat(nextB).isZero();
    }

    @Test
    void recentByShelf_ShouldOnlyReturnPagesFromThatShelf() {
        // arrange
        setup();
        var docsBook = createBook("guide.md", DOCS_SHELF);
        var codeBook = createBook("App.java", CODE_SHELF);
        store.store(List.of(
                createPage("Spring Boot is a Java framework for building applications fast", 0, docsBook),
                createPage("public class Application implements CommandLineRunner", 0, codeBook)
        ));

        // act
        var pages = store.recentByShelf("docs", 5);

        // assert
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().book().shelf().name()).isEqualTo("docs");
    }

    @Test
    void recentByShelf_ShouldOrderByMostRecentFirst() throws InterruptedException {
        // arrange
        setup();
        var older = new Book(Path.of("/test/older.md"), "older.md", DOCS_SHELF,
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        var newer = new Book(Path.of("/test/newer.md"), "newer.md", DOCS_SHELF,
                java.time.Instant.parse("2026-04-19T00:00:00Z"));
        store.store(List.of(createPage("older content about deployment patterns", 0, older)));
        store.store(List.of(createPage("newer content about architecture decisions", 0, newer)));

        // act
        var pages = store.recentByShelf("docs", 5);

        // assert
        assertThat(pages).hasSize(2);
        assertThat(pages.getFirst().book().title()).isEqualTo("newer.md");
        assertThat(pages.getLast().book().title()).isEqualTo("older.md");
    }

    @Test
    void recentByShelf_ShouldRespectLimit() {
        // arrange
        setup();
        var book = createBook("notes.md", DOCS_SHELF);
        store.store(List.of(
                createPage("first chunk about systems thinking and design", 0, book),
                createPage("second chunk about messaging architectures used", 1, book),
                createPage("third chunk about observability and monitoring", 2, book)
        ));

        // act
        var pages = store.recentByShelf("docs", 2);

        // assert
        assertThat(pages).hasSize(2);
    }

    @Test
    void recentByShelf_WhenShelfUnknown_ShouldReturnEmpty() {
        // arrange
        setup();
        store.store(List.of(createPage("anything", 0, createBook("a.md", DOCS_SHELF))));

        // act
        var pages = store.recentByShelf("does-not-exist", 5);

        // assert
        assertThat(pages).isEmpty();
    }

    @Test
    void size_ShouldReflectStoredPages() {
        // arrange
        setup();
        var book = createBook("doc.md", DOCS_SHELF);

        // act & assert
        assertThat(store.size()).isZero();
        store.store(List.of(
                createPage("First chunk about software architecture patterns and design", 0, book),
                createPage("Second chunk about deployment strategies and CI/CD pipelines", 1, book)
        ));
        assertThat(store.size()).isEqualTo(2);
    }
}
