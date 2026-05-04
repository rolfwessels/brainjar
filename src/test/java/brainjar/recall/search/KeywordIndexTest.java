package brainjar.recall.search;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordIndexTest {

    private static final Shelf SHELF = new Shelf("docs");

    private InMemoryPageStore store() {
        return new InMemoryPageStore(new FakeEmbeddingModel());
    }

    private Page page(String content, int idx, Book book) {
        return new Page(Page.generateId(book.sourcePath().toString(), idx), content, idx, book);
    }

    private Book book(String name) {
        return new Book(Path.of("/t/" + name), name, SHELF, Instant.now());
    }

    @Test
    void search_ShouldRankExactLiteralTokenAbovePartialMatches() {
        var store = store();
        var b1 = book("a.md");
        var b2 = book("b.md");
        var b3 = book("c.md");
        store.store(List.of(
                page("Generic prose about reading the environment to configure services.", 0, b1),
                page("The API key is supplied via the BRAVE_API_KEY environment variable.", 0, b2),
                page("Brave search uses an API key to authenticate with the public endpoint.", 0, b3)
        ));
        var index = new KeywordIndex(store);

        var results = index.search("BRAVE_API_KEY", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().page().book().title()).isEqualTo("b.md");
    }

    @Test
    void search_WhenQueryHasNoMatchingTokens_ShouldReturnEmpty() {
        var store = store();
        var b = book("x.md");
        store.store(List.of(page("Java and Spring Boot notes about configuration bindings.", 0, b)));
        var index = new KeywordIndex(store);

        var results = index.search("nonexistentterm-xyz", 3);

        assertThat(results).isEmpty();
    }

    @Test
    void search_ShouldRewardTermFrequencyAndDocumentRarity() {
        var store = store();
        var b1 = book("common.md");
        var b2 = book("rare.md");
        store.store(List.of(
                page("database database database common term everywhere across the corpus", 0, b1),
                page("database and kafka are both mentioned here with distinct specificity", 0, b2)
        ));
        var index = new KeywordIndex(store);

        var results = index.search("kafka", 2);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().page().book().title()).isEqualTo("rare.md");
    }

    @Test
    void tokenise_ShouldLowercaseAndDropShortTokens() {
        assertThat(KeywordIndex.tokenise("Hello A BRAVE_API_KEY gpt-5.2"))
                .containsExactlyInAnyOrder("hello", "brave_api_key", "gpt-5.2");
    }
}
