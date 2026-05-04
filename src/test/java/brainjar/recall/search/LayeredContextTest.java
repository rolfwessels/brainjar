package brainjar.recall.search;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.model.Summary;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import brainjar.recall.store.SummaryStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LayeredContextTest {

    private LayeredContext context;
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
    void wakeUp_ShouldIncludeIdentity() {
        // arrange
        setup();
        context = new LayeredContext("I am Perry, a personal assistant.", store);

        // act
        var result = context.wakeUp();

        // assert
        assertThat(result).contains("Perry");
        assertThat(result).contains("Identity");
    }

    @Test
    void wakeUp_WhenNoIdentity_ShouldStillWork() {
        // arrange
        setup();
        context = new LayeredContext(null, store);

        // act
        var result = context.wakeUp();

        // assert
        assertThat(result).doesNotContain("Identity");
    }

    @Test
    void wakeUp_ShouldIncludeKeyMemories() {
        // arrange
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Spring Boot is the primary framework used for building the application backend", 0, book)
        ));
        context = new LayeredContext(null, store);

        // act
        var result = context.wakeUp();

        // assert
        assertThat(result).contains("Key Memories");
        assertThat(result).contains("Spring Boot");
    }

    @Test
    void wakeUp_ShouldRespectTokenBudget() {
        // arrange
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        for (int i = 0; i < 30; i++) {
            store.store(List.of(
                    createPage("Memory number %d with enough content to take up significant space in the token budget.".formatted(i), i, book)
            ));
        }
        context = new LayeredContext(null, store);

        // act
        var result = context.wakeUp();

        // assert
        assertThat(result.length()).isLessThan(5000);
    }

    @Test
    void recall_ShouldReturnShelfScopedContent() {
        // arrange
        setup();
        var docsBook = createBook("guide.md", DOCS_SHELF);
        var codeBook = createBook("App.java", CODE_SHELF);
        store.store(List.of(
                createPage("Documentation about setting up the development environment and configuration", 0, docsBook),
                createPage("public class Application main entry point with command line runner interface", 0, codeBook)
        ));
        context = new LayeredContext(null, store);

        // act
        var result = context.recall("docs");

        // assert
        assertThat(result).contains("docs");
    }

    @Test
    void search_ShouldReturnFormattedResults() {
        // arrange
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Java 21 features include virtual threads, pattern matching, and record patterns", 0, book)
        ));
        context = new LayeredContext(null, store);

        // act
        var result = context.search("Java 21 features", 5);

        // assert
        assertThat(result).isNotBlank();
        assertThat(result).contains("docs");
        assertThat(result).contains("guide.md");
    }

    @Test
    void search_WhenEmpty_ShouldReturnBlank() {
        // arrange
        setup();
        context = new LayeredContext(null, store);

        // act
        var result = context.search("anything", 5);

        // assert
        assertThat(result).isBlank();
    }

    @Test
    void briefing_WhenStoreEmpty_ShouldReturnBlank() {
        setup();
        context = new LayeredContext(null, store);

        assertThat(context.briefing()).isBlank();
    }

    @Test
    void briefing_ShouldListShelvesWithCounts() {
        setup();
        var tech = new Shelf("tech");
        var prefs = new Shelf("preferences");
        store.store(List.of(
                createPage("uses Java 21", 0, createBook("java.md", tech)),
                createPage("uses Spring Boot", 1, createBook("spring.md", tech)),
                createPage("uses AWS MSK", 2, createBook("aws.md", tech)),
                createPage("prefers dark mode", 0, createBook("volume.md", prefs))
        ));
        context = new LayeredContext(null, store);

        var brief = context.briefing();

        assertThat(brief).contains("Memory briefing");
        assertThat(brief).contains("tech (3)");
        assertThat(brief).contains("preferences (1)");
    }

    @Test
    void briefing_ShouldStayUnderTokenBudget() {
        setup();
        for (int i = 0; i < 50; i++) {
            var shelf = new Shelf("shelf-" + (i % 5));
            store.store(List.of(
                    createPage("Some content number " + i + " with enough words to be meaningful", i,
                            createBook("doc-" + i + ".md", shelf))
            ));
        }
        context = new LayeredContext(null, store);

        var brief = context.briefing();

        assertThat(brief.length()).isLessThan(1200);
        assertThat(brief).doesNotContain("Identity");
    }

    @Test
    void briefing_WhenSummariesAvailable_ShouldPreferKeySentences() {
        setup();
        var summaries = new SummaryStore();
        var book = createBook("notes.md", new Shelf("tech"));
        var page = createPage("a verbose page that would otherwise be used as snippet text for briefing", 0, book);
        store.store(List.of(page));
        summaries.put(new Summary(page.id(), List.of("Java"), List.of("language"),
                "Java is the primary language.", List.of()));
        context = new LayeredContext(null, store, null, summaries);

        var brief = context.briefing();

        assertThat(brief).contains("Java is the primary language.");
        assertThat(brief).doesNotContain("verbose page that would otherwise");
    }

    @Test
    void briefing_WhenManyShelves_ShouldCapAndIndicateMore() {
        setup();
        for (int i = 0; i < 12; i++) {
            var shelf = new Shelf("s" + i);
            store.store(List.of(createPage("c" + i, 0, createBook("b" + i + ".md", shelf))));
        }
        context = new LayeredContext(null, store);

        var brief = context.briefing();

        assertThat(brief).contains("+4 more");
    }

    @Test
    void briefingForUser_ShouldStripPrefixAndExcludeOtherUsers() {
        setup();
        var mineWines = new Shelf("user:user-42:wines");
        var otherSecrets = new Shelf("user:other-user:secrets");
        var docs = new Shelf("docs");
        store.store(List.of(
                createPage("Likes Railroad Red", 0, createBook("mine-wines.md", mineWines)),
                createPage("Other user's secret note", 0, createBook("other-secrets.md", otherSecrets)),
                createPage("Mined documentation about Spring Boot", 0, createBook("guide.md", docs))
        ));
        context = new LayeredContext(null, store);

        var brief = context.briefing("user-42");

        assertThat(brief).contains("wines (1)");
        assertThat(brief).contains("docs (1)");
        assertThat(brief).doesNotContain("user:user-42:wines");
        assertThat(brief).doesNotContain("secrets");
        assertThat(brief).doesNotContain("user:other-user");
    }

    @Test
    void briefingForUser_ShouldUseDisplayNamesInRecentLines() {
        setup();
        var mineWines = new Shelf("user:user-42:wines");
        store.store(List.of(
                createPage("Likes Railroad Red", 0, createBook("mine-wines.md", mineWines))
        ));
        context = new LayeredContext(null, store);

        var brief = context.briefing("user-42");

        assertThat(brief).contains("[wines]");
        assertThat(brief).doesNotContain("[user:");
    }

    @Test
    void briefingForUser_WhenUserHasNothingVisible_ShouldReturnBlank() {
        setup();
        var otherUserShelf = new Shelf("user:other-user:wines");
        store.store(List.of(
                createPage("Other user note", 0, createBook("other-wines.md", otherUserShelf))
        ));
        context = new LayeredContext(null, store);

        assertThat(context.briefing("user-42")).isBlank();
    }

    @Test
    void searchForUser_ShouldExcludeOtherUsersPages() {
        setup();
        var mineShelf = new Shelf("user:user-42:notes");
        var otherShelf = new Shelf("user:other-user:notes");
        var docsShelf = new Shelf("docs");
        store.store(List.of(
                createPage("Java 21 features include virtual threads patterns", 0,
                        createBook("mine.md", mineShelf)),
                createPage("Java 21 features include records and sealed types", 0,
                        createBook("other.md", otherShelf)),
                createPage("Java 21 documentation overview for developers", 0,
                        createBook("guide.md", docsShelf))
        ));
        context = new LayeredContext(null, store);

        var result = context.search("Java 21 features", 5, "user-42");

        assertThat(result).contains("mine.md");
        assertThat(result).contains("guide.md");
        assertThat(result).doesNotContain("other.md");
        assertThat(result).doesNotContain("user:other-user");
    }

    @Test
    void searchForUser_ShouldStripUserPrefixFromShelfLabel() {
        setup();
        var mineShelf = new Shelf("user:user-42:notes");
        store.store(List.of(
                createPage("Java 21 features include virtual threads", 0,
                        createBook("mine.md", mineShelf))
        ));
        context = new LayeredContext(null, store);

        var result = context.search("Java 21", 5, "user-42");

        assertThat(result).contains("[notes/mine.md]");
        assertThat(result).doesNotContain("user:user-42");
    }

    @Test
    void recall_WithQuery_ShouldFilterToShelfAndQuery() {
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Java 21 brings virtual threads, records, and pattern matching to the language", 0, book),
                createPage("Python 3.12 introduces type parameter syntax improvements for generic classes", 1, book)
        ));
        context = new LayeredContext(null, store);

        var result = context.recall("docs", "Java");

        assertThat(result).contains("Java");
    }

    @Test
    void recall_WithoutQuery_ShouldReturnRecentPages() {
        setup();
        var book = createBook("guide.md", DOCS_SHELF);
        store.store(List.of(
                createPage("Documentation about Spring Boot configuration patterns and bootstrapping", 0, book)
        ));
        context = new LayeredContext(null, store);

        var result = context.recall("docs", null);

        assertThat(result).contains("Spring Boot");
    }

    @Test
    void recall_WhenShelfUnknown_ShouldReturnBlank() {
        setup();
        context = new LayeredContext(null, store);

        assertThat(context.recall("nonexistent")).isBlank();
        assertThat(context.recall("nonexistent", "anything")).isBlank();
    }
}
