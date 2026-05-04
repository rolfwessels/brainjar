package brainjar.recall.store;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilePageStoreTest {

    @TempDir
    Path tempDir;

    private static final Shelf DOCS_SHELF = new Shelf("docs");

    private static Book createBook(String filename) {
        return new Book(Path.of("/test/" + filename), filename, DOCS_SHELF, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Page createPage(String content, int chunkIndex, Book book) {
        var id = Page.generateId(book.sourcePath().toString(), chunkIndex);
        return new Page(id, content, chunkIndex, book);
    }

    @Test
    void store_AndReload_ShouldPreservePages() {
        // arrange
        var filePath = tempDir.resolve("store.json");
        var model = new FakeEmbeddingModel();

        var store1 = new FilePageStore(model, filePath);
        var book = createBook("guide.md");
        store1.store(List.of(
                createPage("Spring Boot is a framework for building Java applications quickly and easily", 0, book),
                createPage("Docker containers provide lightweight isolated environments for running services", 1, book)
        ));
        assertThat(store1.size()).isEqualTo(2);

        // act
        var store2 = new FilePageStore(model, filePath);

        // assert
        assertThat(store2.size()).isEqualTo(2);
        var results = store2.search("Spring Boot Java", 5);
        assertThat(results).isNotEmpty();
    }

    @Test
    void emptyStore_ShouldSerializeAndDeserializeCleanly() {
        // arrange
        var filePath = tempDir.resolve("empty.json");
        var model = new FakeEmbeddingModel();

        var store1 = new FilePageStore(model, filePath);
        store1.store(List.of(
                createPage("Temporary content that will be stored and then the store will be recreated", 0, createBook("temp.md"))
        ));

        // act
        var store2 = new FilePageStore(model, filePath);

        // assert
        assertThat(store2.size()).isEqualTo(1);
    }

    @Test
    void newStore_WhenFileDoesNotExist_ShouldStartEmpty() {
        // arrange
        var filePath = tempDir.resolve("nonexistent.json");
        var model = new FakeEmbeddingModel();

        // act
        var store = new FilePageStore(model, filePath);

        // assert
        assertThat(store.size()).isZero();
    }
}
