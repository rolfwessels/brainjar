package brainjar.recall.ingest;

import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import brainjar.recall.store.SummaryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MinerTest {

    @TempDir
    Path tempDir;

    private Miner miner;
    private InMemoryPageStore store;
    private SummaryStore summaryStore;
    private KnowledgeGraph knowledgeGraph;

    private static final Shelf DOCS_SHELF = new Shelf("docs");

    private void setup() {
        store = new InMemoryPageStore(new FakeEmbeddingModel());
        summaryStore = new SummaryStore();
        knowledgeGraph = new KnowledgeGraph("jdbc:sqlite::memory:");
        miner = new Miner(new Chunker(), new SummaryCompressor(), store, summaryStore, knowledgeGraph);
    }

    @AfterEach
    void cleanup() {
        if (knowledgeGraph != null) {
            knowledgeGraph.close();
        }
    }

    private Path createFile(String name, String content) throws IOException {
        var file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void mineFile_ShouldStorePages() throws IOException {
        // arrange
        setup();
        var file = createFile("test.md", "This is a test document with enough content to be stored as a page in the system.");

        // act
        int count = miner.mineFile(file, DOCS_SHELF);

        // assert
        assertThat(count).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void mineFile_WhenEmpty_ShouldSkip() throws IOException {
        // arrange
        setup();
        var file = createFile("empty.md", "");

        // act
        int count = miner.mineFile(file, DOCS_SHELF);

        // assert
        assertThat(count).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void mineDirectory_ShouldProcessAllFiles() throws IOException {
        // arrange
        setup();
        createFile("one.md", "First document containing information about software architecture and design patterns.");
        createFile("two.md", "Second document containing information about testing strategies and quality assurance.");

        // act
        int count = miner.mineDirectory(tempDir, DOCS_SHELF);

        // assert
        assertThat(count).isEqualTo(2);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void mineFile_WhenCalledTwice_ShouldBeIdempotent() throws IOException {
        // arrange
        setup();
        var file = createFile("test.md", "Content about Java programming and Spring Boot framework for building applications.");

        // act
        miner.mineFile(file, DOCS_SHELF);
        miner.mineFile(file, DOCS_SHELF);

        // assert
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void mineFile_WhenPathDoesNotExist_ShouldReturnZero() {
        // arrange
        setup();

        // act
        int count = miner.mineFile(tempDir.resolve("nonexistent.md"), DOCS_SHELF);

        // assert
        assertThat(count).isZero();
    }

    @Test
    void mineDirectory_ShouldIncludeSubdirectories() throws IOException {
        // arrange
        setup();
        var sub = Files.createDirectory(tempDir.resolve("sub"));
        createFile("root.md", "Root level document about system configuration and environment setup.");
        Files.writeString(sub.resolve("nested.md"), "Nested document about database migrations and schema management.");

        // act
        int count = miner.mineDirectory(tempDir, DOCS_SHELF);

        // assert
        assertThat(count).isEqualTo(2);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void mineFile_ShouldPopulateSummaryStore() throws IOException {
        // arrange
        setup();
        var file = createFile("java-guide.md",
                "Spring Boot integrates with LangChain4j. Perry uses OpenAI for chat. Brave is the web search backend.");

        // act
        miner.mineFile(file, DOCS_SHELF);

        // assert
        assertThat(summaryStore.size()).isEqualTo(1);
        var summary = summaryStore.all().iterator().next();
        assertThat(summary.entities()).isNotEmpty();
    }

    @Test
    void mineFile_ShouldPopulateKnowledgeGraph() throws IOException {
        // arrange
        setup();
        var file = createFile("discord-setup.md",
                "Perry is a Discord bot that uses Spring Boot. Perry integrates with LangChain4j and OpenAI.");

        // act
        miner.mineFile(file, DOCS_SHELF);

        // assert
        var triples = knowledgeGraph.query("discord-setup");
        assertThat(triples).isNotEmpty();
        assertThat(triples).allMatch(t -> t.predicate().equals("mentions"));
    }

    @Test
    void mineFile_WhenNoKnowledgeGraph_ShouldStillStoreSummaries() throws IOException {
        // arrange
        setup();
        miner = new Miner(new Chunker(), new SummaryCompressor(), store, summaryStore, null);
        var file = createFile("standalone.md",
                "A short text about HotChocolate and GraphQL and Circulor and Battery Passport.");

        // act
        miner.mineFile(file, DOCS_SHELF);

        // assert
        assertThat(summaryStore.size()).isEqualTo(1);
    }
}
