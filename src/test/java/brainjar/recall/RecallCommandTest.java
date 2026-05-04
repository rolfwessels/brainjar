package brainjar.recall;

import brainjar.recall.ingest.Chunker;
import brainjar.recall.ingest.Miner;
import brainjar.recall.kg.Entity;
import brainjar.recall.kg.Triple;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallCommandTest {

    @TempDir
    Path tempDir;

    private InMemoryPageStore store;
    private Miner miner;

    private void setup() {
        store = new InMemoryPageStore(new FakeEmbeddingModel());
        miner = new Miner(new Chunker(), store);
    }

    @Test
    void parseArgs_WhenMine_ShouldExtractPathsAndShelf() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--mine", "/docs", "/src", "--shelf", "myshelf"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.MINE);
        assertThat(result.paths()).containsExactly(Path.of("/docs"), Path.of("/src"));
        assertThat(result.shelfName()).isEqualTo("myshelf");
    }

    @Test
    void parseArgs_WhenMineNoShelf_ShouldDefaultToDirectoryName() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--mine", "/home/user/projects/docs"});

        // assert
        assertThat(result.shelfName()).isEqualTo("docs");
    }

    @Test
    void parseArgs_WhenSearch_ShouldExtractQuery() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--search", "how does chunking work"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.SEARCH);
        assertThat(result.query()).isEqualTo("how does chunking work");
    }

    @Test
    void parseArgs_WhenSpringProfilePrepended_ShouldStillParseSearch() {
        // act
        var result = RecallCommand.parseArgs(
                new String[]{"--spring.profiles.active=cli", "--search", "chunking", "overlap"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.SEARCH);
        assertThat(result.query()).isEqualTo("chunking overlap");
    }

    @Test
    void parseArgs_WhenSearchWithShelfAndMax_ShouldExtractAll() {
        // act
        var result = RecallCommand.parseArgs(
                new String[]{"--search", "embeddings", "--shelf", "docs", "--max", "10"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.SEARCH);
        assertThat(result.query()).isEqualTo("embeddings");
        assertThat(result.shelfName()).isEqualTo("docs");
        assertThat(result.maxResults()).isEqualTo(10);
    }

    @Test
    void parseArgs_WhenRemoveShelf_ShouldExtractShelfName() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--remove-shelf", "docs"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.REMOVE_SHELF);
        assertThat(result.shelfName()).isEqualTo("docs");
    }

    @Test
    void parseArgs_WhenBriefing_ShouldSetCommand() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--briefing"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.BRIEFING);
    }

    @Test
    void parseArgs_WhenListShelves_ShouldSetCommand() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--list-shelves"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.LIST_SHELVES);
    }

    @Test
    void parseArgs_WhenLatest_ShouldSetCommand() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--latest"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.LATEST);
        assertThat(result.maxResults()).isZero();
        assertThat(result.shelfName()).isNull();
    }

    @Test
    void parseArgs_WhenLatestWithShortLimitAndShelf_ShouldExtractAll() {
        // act
        var result = RecallCommand.parseArgs(
                new String[]{"--latest", "-n", "5", "--shelf", "movies"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.LATEST);
        assertThat(result.maxResults()).isEqualTo(5);
        assertThat(result.shelfName()).isEqualTo("movies");
    }

    @Test
    void parseArgs_WhenLatestWithMaxFlag_ShouldExtractLimit() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--latest", "--max", "20"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.LATEST);
        assertThat(result.maxResults()).isEqualTo(20);
    }

    @Test
    void parseArgs_WhenListJobs_ShouldSetCommand() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--list-jobs"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.LIST_JOBS);
    }

    @Test
    void parseArgs_WhenExportKgNoPath_ShouldSetCommand() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--export-kg"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.EXPORT_KG);
        assertThat(result.paths()).isEmpty();
    }

    @Test
    void parseArgs_WhenExportKgWithPath_ShouldCaptureOutputPath() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--export-kg", "/tmp/out.cypher"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.EXPORT_KG);
        assertThat(result.paths()).containsExactly(Path.of("/tmp/out.cypher"));
    }

    @Test
    void toRelType_ShouldUppercaseAndSanitize() {
        // act & assert
        assertThat(RecallCommand.toRelType("works_at")).isEqualTo("WORKS_AT");
        assertThat(RecallCommand.toRelType("has-name")).isEqualTo("HAS_NAME");
        assertThat(RecallCommand.toRelType("42things")).isEqualTo("R_42THINGS");
        assertThat(RecallCommand.toRelType("")).isEqualTo("RELATES");
    }

    @Test
    void renderCypher_ShouldProduceLoadableScript() {
        // arrange
        var entities = List.of(
                new Entity("rolf_wessels", "Rolf Wessels", "person", Instant.parse("2026-01-01T00:00:00Z")),
                new Entity("circulor", "Circulor", "org", Instant.parse("2026-01-01T00:00:00Z")));
        var triples = List.of(
                new Triple("t1", "rolf_wessels", "works_at", "circulor",
                        LocalDate.of(2023, 1, 1), null, 1.0, "p_abc"),
                new Triple("t2", "rolf_wessels", "knows", "circulor",
                        null, null, 0.8, null));

        // act
        var cypher = RecallCommand.renderCypher(entities, triples);

        // assert
        assertThat(cypher).contains("CREATE CONSTRAINT entity_id");
        assertThat(cypher).contains("MERGE (e:Entity {id: row.id})");
        assertThat(cypher).contains("Rolf Wessels");
        assertThat(cypher).contains("MERGE (s)-[r:WORKS_AT]->(o)");
        assertThat(cypher).contains("MERGE (s)-[r:KNOWS]->(o)");
        assertThat(cypher).contains("validFrom: '2023-01-01'");
        assertThat(cypher).contains("validTo: null");
        assertThat(cypher).contains("sourcePageId: null");
    }

    @Test
    void parseArgs_WhenExportKgWithFormat_ShouldCaptureFormat() {
        // act
        var result = RecallCommand.parseArgs(
                new String[]{"--export-kg", "/tmp/out", "--format", "csv"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.EXPORT_KG);
        assertThat(result.format()).isEqualTo("csv");
        assertThat(result.paths()).containsExactly(Path.of("/tmp/out"));
    }

    @Test
    void renderNodesTable_Csv_ShouldEmitHeaderAndRows() {
        // arrange
        var entities = List.of(
                new Entity("rolf", "Rolf", "person", Instant.parse("2026-01-01T00:00:00Z")),
                new Entity("circulor", "Circulor, Ltd", "org", Instant.parse("2026-01-01T00:00:00Z")));

        // act
        var csv = RecallCommand.renderNodesTable(entities, ",", "csv");

        // assert
        assertThat(csv.lines().toList()).containsExactly(
                "id,name,type",
                "rolf,Rolf,person",
                "circulor,\"Circulor, Ltd\",org");
    }

    @Test
    void renderEdgesTable_Csv_ShouldIncludeTemporalFields() {
        // arrange
        var triples = List.of(
                new Triple("t1", "rolf", "works_at", "circulor",
                        LocalDate.of(2023, 1, 1), null, 1.0, "p_abc"));

        // act
        var csv = RecallCommand.renderEdgesTable(triples, ",", "csv");

        // assert
        assertThat(csv.lines().toList()).containsExactly(
                "source,target,predicate,validFrom,validTo,confidence,sourcePageId",
                "rolf,circulor,works_at,2023-01-01,,1.0,p_abc");
    }

    @Test
    void renderEdgesTable_Tsv_ShouldUseTabs() {
        // arrange
        var triples = List.of(
                new Triple("t1", "rolf", "works_at", "circulor",
                        null, null, 0.9, null));

        // act
        var tsv = RecallCommand.renderEdgesTable(triples, "\t", "tsv");

        // assert
        var lines = tsv.lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("source\ttarget\tpredicate\tvalidFrom\tvalidTo\tconfidence\tsourcePageId");
        assertThat(lines.get(1)).isEqualTo("rolf\tcirculor\tworks_at\t\t\t0.9\t");
    }

    @Test
    void escapeField_Csv_ShouldQuoteCommasAndDoubleUpQuotes() {
        // act & assert
        assertThat(RecallCommand.escapeField("plain", "csv")).isEqualTo("plain");
        assertThat(RecallCommand.escapeField("a,b", "csv")).isEqualTo("\"a,b\"");
        assertThat(RecallCommand.escapeField("he said \"hi\"", "csv"))
                .isEqualTo("\"he said \"\"hi\"\"\"");
    }

    @Test
    void escapeField_Tsv_ShouldBackslashEscapeTabsAndNewlines() {
        // act & assert
        assertThat(RecallCommand.escapeField("a\tb", "tsv")).isEqualTo("a\\tb");
        assertThat(RecallCommand.escapeField("line1\nline2", "tsv")).isEqualTo("line1\\nline2");
        assertThat(RecallCommand.escapeField("c:\\path", "tsv")).isEqualTo("c:\\\\path");
    }

    @Test
    void renderCypher_ShouldEscapeSingleQuotes() {
        // arrange
        var entities = List.of(
                new Entity("obrien", "O'Brien", "person", Instant.parse("2026-01-01T00:00:00Z")));

        // act
        var cypher = RecallCommand.renderCypher(entities, List.of());

        // assert
        assertThat(cypher).contains("O\\'Brien");
    }

    @Test
    void parseArgs_WhenNoCommand_ShouldReturnNone() {
        // act
        var result = RecallCommand.parseArgs(new String[]{"--other", "value"});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.NONE);
    }

    @Test
    void parseArgs_WhenEmpty_ShouldReturnNone() {
        // act
        var result = RecallCommand.parseArgs(new String[]{});

        // assert
        assertThat(result.command()).isEqualTo(RecallCommand.Command.NONE);
    }

    @Test
    void truncate_WhenShort_ShouldReturnAsIs() {
        // act & assert
        assertThat(RecallCommand.truncate("short text", 200)).isEqualTo("short text");
    }

    @Test
    void truncate_WhenLong_ShouldAddEllipsis() {
        // act
        var result = RecallCommand.truncate("a".repeat(300), 200);

        // assert
        assertThat(result).hasSize(203);
        assertThat(result).endsWith("...");
    }

    @Test
    void truncate_ShouldCollapseWhitespace() {
        // act & assert
        assertThat(RecallCommand.truncate("hello\n  world\n\nfoo", 200)).isEqualTo("hello world foo");
    }

    @Test
    void mineAndSearch_Integration() throws IOException {
        // arrange
        setup();
        Files.writeString(tempDir.resolve("test.md"),
                "BrainJar uses LangChain4j for embedding and semantic search capabilities.");

        // act - mine
        var shelf = new Shelf("docs");
        miner.mineDirectory(tempDir, shelf);

        // act - search
        var results = store.search("LangChain4j embedding", 5);

        // assert
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().page().content()).contains("LangChain4j");
    }

    @Test
    void deleteByShelf_ShouldRemoveOnlyThatShelf() throws IOException {
        // arrange
        setup();
        var docsDir = Files.createDirectory(tempDir.resolve("docs"));
        var codeDir = Files.createDirectory(tempDir.resolve("code"));
        Files.writeString(docsDir.resolve("doc.md"),
                "Documentation about the project architecture and how all the components fit together in the system.");
        Files.writeString(codeDir.resolve("code.md"),
                "Source code implementing the feature logic with proper error handling and validation throughout the codebase.");

        miner.mineDirectory(docsDir, new Shelf("docs"));
        miner.mineDirectory(codeDir, new Shelf("code"));
        assertThat(store.size()).isEqualTo(2);

        // act
        int removed = store.deleteByShelf("docs");

        // assert
        assertThat(removed).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
    }
}
