package brainjar.recall.export;

import brainjar.recall.ingest.Chunker;
import brainjar.recall.ingest.Miner;
import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookExporterTest {

    @TempDir
    Path tempDir;

    private BookExporter bookExporter;
    private InMemoryPageStore store;
    private Miner miner;

    private void setup() {
        bookExporter = new BookExporter();
        store = new InMemoryPageStore(new FakeEmbeddingModel());
        miner = new Miner(new Chunker(), store);
    }

    @Test
    void buildFilename_WithMdTitle_ShouldProduceCleanFilename() {
        // arrange — mined file: title already ends in .md
        var book = sampleBook("notes.md", "notes", Instant.parse("2026-05-01T10:00:00Z"));

        // act & assert
        assertThat(BookExporter.buildFilename(book)).isEqualTo("2026-05-01-notes.md");
    }

    @Test
    void buildFilename_WithConversationalTitle_ShouldAddExtensionAndReplaceSpaces() {
        // arrange — conversational capture: title has spaces and no extension
        var book = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), Instant.parse("2026-05-05T10:00:00Z"));

        // act & assert
        assertThat(BookExporter.buildFilename(book)).isEqualTo("2026-05-05-wines-captures-2026-05-05.md");
    }

    @Test
    void buildFilename_WithNullLastModified_ShouldUsePlaceholder() {
        // arrange
        var book = new Book(Path.of("/docs/notes.md"), "notes.md", new Shelf("general"), null);

        // act & assert
        assertThat(BookExporter.buildFilename(book)).isEqualTo("0000-00-00-notes.md");
    }

    @Test
    void buildFilename_WithSpacesAndMdExtension_ShouldReplaceSpacesAndPreserveExtension() {
        // arrange — title has spaces AND already ends in .md
        var book = new Book(Path.of("/docs/my notes.md"), "my notes.md", new Shelf("docs"),
                Instant.parse("2026-05-01T10:00:00Z"));

        // act & assert
        assertThat(BookExporter.buildFilename(book)).isEqualTo("2026-05-01-my-notes.md");
    }

    @Test
    void buildContent_ShouldConcatenatePagesInChunkOrder() {
        // arrange
        var book = sampleBook("notes.md", "notes", Instant.now());
        var page0 = new Page(Page.generateId("/notes.md", 0), "First chunk.", 0, book);
        var page1 = new Page(Page.generateId("/notes.md", 1), "Second chunk.", 1, book);

        // act — pass in reversed order to verify sorting by chunkIndex
        var content = BookExporter.buildContent(List.of(page1, page0));

        // assert
        assertThat(content).isEqualTo("First chunk.\n\nSecond chunk.");
    }

    @Test
    void groupByBook_ShouldGroupPagesFromSameBook() {
        // arrange
        var book = sampleBook("notes.md", "notes", Instant.now());
        var page0 = new Page(Page.generateId("/notes.md", 0), "Chunk A.", 0, book);
        var page1 = new Page(Page.generateId("/notes.md", 1), "Chunk B.", 1, book);

        // act
        var groups = BookExporter.groupByBook(List.of(page0, page1));

        // assert
        assertThat(groups).hasSize(1);
        assertThat(groups.get(book)).containsExactly(page0, page1);
    }

    @Test
    void groupByBook_ShouldSeparatePagesFromDifferentBooks() {
        // arrange
        var book1 = sampleBook("a.md", "a", Instant.parse("2026-01-01T00:00:00Z"));
        var book2 = sampleBook("b.md", "b", Instant.parse("2026-01-01T00:00:00Z"));
        var page1 = new Page(Page.generateId("/a.md", 0), "Content A.", 0, book1);
        var page2 = new Page(Page.generateId("/b.md", 0), "Content B.", 0, book2);

        // act
        var groups = BookExporter.groupByBook(List.of(page1, page2));

        // assert
        assertThat(groups).hasSize(2);
    }

    @Test
    void export_ShouldWriteOneFilePerBook() throws IOException {
        // arrange
        setup();
        var outputDir = tempDir.resolve("export");
        Files.writeString(tempDir.resolve("notes.md"),
                "This is the first note. It contains enough text to be stored as a page in the system.");

        miner.mineFile(tempDir.resolve("notes.md"), new Shelf("docs"));
        var pages = store.recent(Integer.MAX_VALUE);

        // act
        int written = bookExporter.export(pages, outputDir, null);

        // assert
        var docsDir = outputDir.resolve("docs");
        assertThat(written).isEqualTo(1);
        var exportedFiles = Files.list(docsDir).toList();
        assertThat(exportedFiles).hasSize(1);
        assertThat(exportedFiles.getFirst().getFileName().toString()).endsWith("-notes.md");
        assertThat(Files.readString(exportedFiles.getFirst())).contains("first note");
    }

    @Test
    void export_WithConversationalBook_ShouldProduceValidMdFileWithNoSpaces() throws IOException {
        // arrange — simulates pages produced by remember(), not --mine
        setup();
        var outputDir = tempDir.resolve("export");
        var book = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), Instant.parse("2026-05-05T10:00:00Z"));
        var page = new Page(Page.generateId("/captures/wines/2026-05-05.md", 0),
                "Likes Eikendal Charisma.", 0, book);

        // act
        int written = bookExporter.export(List.of(page), outputDir, null);

        // assert
        assertThat(written).isEqualTo(1);
        var files = Files.list(outputDir.resolve("wines")).toList();
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().getFileName().toString())
                .isEqualTo("2026-05-05-wines-captures-2026-05-05.md");
        assertThat(Files.readString(files.getFirst())).contains("Eikendal Charisma");
    }

    @Test
    void export_WithMultipleCapturesToSameShelfSameDay_ShouldProduceSingleFile() throws IOException {
        // arrange — two pages captured at different times, same sourcePath (Instant.now() differs per call)
        setup();
        var outputDir = tempDir.resolve("export");
        var earlier = Instant.parse("2026-05-05T10:00:00Z");
        var later   = Instant.parse("2026-05-05T11:00:00Z");
        var book1 = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), earlier);
        var book2 = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), later);
        var page0 = new Page(Page.generateId("/captures/wines/2026-05-05.md", 0),
                "Likes Eikendal Charisma.", 0, book1);
        var page1 = new Page(Page.generateId("/captures/wines/2026-05-05.md", 1),
                "Also likes Meerlust.", 1, book2);

        // act
        int written = bookExporter.export(List.of(page0, page1), outputDir, null);

        // assert — one file containing both memories
        assertThat(written).isEqualTo(1);
        var files = Files.list(outputDir.resolve("wines")).toList();
        assertThat(files).hasSize(1);
        var content = Files.readString(files.getFirst());
        assertThat(content).contains("Eikendal Charisma");
        assertThat(content).contains("Meerlust");
    }

    @Test
    void export_WithShelfFilter_ShouldOnlyWriteMatchingShelf() throws IOException {
        // arrange
        setup();
        var outputDir = tempDir.resolve("export");
        Files.writeString(tempDir.resolve("notes.md"),
                "Note content for the docs shelf, enough to form a complete page entry.");
        Files.writeString(tempDir.resolve("code.md"),
                "Code content for the code shelf, enough to form a complete page entry.");

        miner.mineFile(tempDir.resolve("notes.md"), new Shelf("docs"));
        miner.mineFile(tempDir.resolve("code.md"), new Shelf("code"));
        var pages = store.recent(Integer.MAX_VALUE);

        // act
        int written = bookExporter.export(pages, outputDir, "docs");

        // assert
        assertThat(written).isEqualTo(1);
        assertThat(outputDir.resolve("docs")).exists();
        assertThat(outputDir.resolve("code")).doesNotExist();
    }

    @Test
    void groupByBook_ShouldMergePagesThatShareSourcePathButDifferInLastModified() {
        // arrange — simulates conversational captures: same sourcePath, Instant.now() differs per call
        var earlier = Instant.parse("2026-05-05T10:00:00Z");
        var later   = Instant.parse("2026-05-05T11:00:00Z");
        var book1 = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), earlier);
        var book2 = new Book(Path.of("/captures/wines/2026-05-05.md"), "wines captures 2026-05-05",
                new Shelf("wines"), later);
        var page0 = new Page(Page.generateId("/captures/wines/2026-05-05.md", 0), "Likes Eikendal Charisma.", 0, book1);
        var page1 = new Page(Page.generateId("/captures/wines/2026-05-05.md", 1), "Also likes Meerlust.", 1, book2);

        // act
        var groups = BookExporter.groupByBook(List.of(page0, page1));

        // assert — one group despite different lastModified, representative uses the later timestamp
        assertThat(groups).hasSize(1);
        var representative = groups.keySet().iterator().next();
        assertThat(representative.lastModified()).isEqualTo(later);
        assertThat(groups.get(representative)).containsExactlyInAnyOrder(page0, page1);
    }

    @Test
    void export_WhenEmpty_ShouldWriteZeroFiles() throws IOException {
        // arrange
        setup();
        var outputDir = tempDir.resolve("export");

        // act
        int written = bookExporter.export(List.of(), outputDir, null);

        // assert
        assertThat(written).isZero();
        assertThat(outputDir).doesNotExist();
    }

    private static Book sampleBook(String title, String shelfName, Instant lastModified) {
        return new Book(Path.of("/docs/" + title), title, new Shelf(shelfName), lastModified);
    }
}
