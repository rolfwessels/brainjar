package brainjar.recall.ingest;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private Chunker chunker;

    private static Book createBook() {
        return new Book(Path.of("/test/doc.md"), "Test Doc", new Shelf("docs"), Instant.now());
    }

    private void setup() {
        chunker = new Chunker();
    }

    @Test
    void chunk_WhenNullInput_ShouldReturnEmptyList() {
        // arrange
        setup();

        // act
        var result = chunker.chunk(null, createBook());

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    void chunk_WhenBlankInput_ShouldReturnEmptyList() {
        // arrange
        setup();

        // act
        var result = chunker.chunk("   \n  ", createBook());

        // assert
        assertThat(result).isEmpty();
    }

    @Test
    void chunk_WhenShortText_ShouldReturnSinglePage() {
        // arrange
        setup();
        var text = "This is a short document with enough content to pass the minimum size threshold for chunking.";

        // act
        var result = chunker.chunk(text, createBook());

        // assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().content()).isEqualTo(text);
        assertThat(result.getFirst().chunkIndex()).isZero();
    }

    @Test
    void chunk_WhenLongText_ShouldSplitWithOverlap() {
        // arrange
        var smallChunker = new Chunker(100, 20);
        var text = "a".repeat(250);

        // act
        var result = smallChunker.chunk(text, createBook());

        // assert
        assertThat(result.size()).isGreaterThan(1);
        for (int i = 1; i < result.size(); i++) {
            var previousEnd = result.get(i - 1).content();
            var currentStart = result.get(i).content();
            var overlapFromPrevious = previousEnd.substring(previousEnd.length() - 20);
            var overlapFromCurrent = currentStart.substring(0, 20);
            assertThat(overlapFromPrevious).isEqualTo(overlapFromCurrent);
        }
    }

    @Test
    void chunk_WhenParagraphBoundaryPastMidpoint_ShouldBreakAtParagraph() {
        // arrange
        var smallChunker = new Chunker(100, 20);
        var firstPart = "a".repeat(60);
        var secondPart = "b".repeat(80);
        var text = firstPart + "\n\n" + secondPart;

        // act
        var result = smallChunker.chunk(text, createBook());

        // assert
        assertThat(result.size()).isGreaterThanOrEqualTo(2);
        assertThat(result.getFirst().content()).isEqualTo(firstPart);
    }

    @Test
    void chunk_WhenLineBoundaryPastMidpoint_ShouldBreakAtLine() {
        // arrange
        var smallChunker = new Chunker(100, 20);
        var firstPart = "a".repeat(60);
        var secondPart = "b".repeat(80);
        var text = firstPart + "\n" + secondPart;

        // act
        var result = smallChunker.chunk(text, createBook());

        // assert
        assertThat(result.size()).isGreaterThanOrEqualTo(2);
        assertThat(result.getFirst().content()).isEqualTo(firstPart);
    }

    @Test
    void chunk_WhenBoundaryBeforeMidpoint_ShouldNotBreakThere() {
        // arrange
        var smallChunker = new Chunker(100, 20);
        var earlyBreak = "a".repeat(30);
        var rest = "b".repeat(80);
        var text = earlyBreak + "\n\n" + rest;

        // act
        var result = smallChunker.chunk(text, createBook());

        // assert
        assertThat(result.getFirst().content().length()).isGreaterThan(30);
    }

    @Test
    void chunk_WhenChunkBelowMinSize_ShouldMergeWithPrevious() {
        // arrange
        var smallChunker = new Chunker(100, 10);
        var main = "a".repeat(90);
        var tiny = "b".repeat(30);
        var text = main + "\n\n" + tiny;

        // act
        var result = smallChunker.chunk(text, createBook());

        // assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().content()).contains("a".repeat(90));
        assertThat(result.getFirst().content()).contains("b".repeat(30));
    }

    @Test
    void chunk_ShouldProduceDeterministicIds() {
        // arrange
        setup();
        var text = "a".repeat(100);
        var book = createBook();

        // act
        var first = chunker.chunk(text, book);
        var second = chunker.chunk(text, book);

        // assert
        assertThat(first).hasSameSizeAs(second);
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).id()).isEqualTo(second.get(i).id());
        }
    }

    @Test
    void chunk_WhenUnicodeContent_ShouldHandleCorrectly() {
        // arrange
        setup();
        var text = "日本語テスト ".repeat(20);

        // act
        var result = chunker.chunk(text, createBook());

        // assert
        assertThat(result).isNotEmpty();
        var reconstructed = String.join("", result.stream().map(Page::content).toList());
        assertThat(reconstructed).contains("日本語テスト");
    }

    @Test
    void chunk_ShouldSetSequentialChunkIndices() {
        // arrange
        setup();
        var text = "a".repeat(2000);

        // act
        var result = chunker.chunk(text, createBook());

        // assert
        assertThat(result.size()).isGreaterThan(1);
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i).chunkIndex()).isEqualTo(i);
        }
    }

    @Test
    void chunk_AllPagesShouldReferenceTheBook() {
        // arrange
        setup();
        var book = createBook();
        var text = "a".repeat(2000);

        // act
        var result = chunker.chunk(text, book);

        // assert
        assertThat(result).allMatch(page -> page.book() == book);
    }

    @Test
    void pageGenerateId_ShouldStartWithPagePrefix() {
        // act
        var id = Page.generateId("/test/file.md", 0);

        // assert
        assertThat(id).startsWith("page_");
        assertThat(id).hasSize("page_".length() + 24);
    }

    @Test
    void pageGenerateId_SamePath_DifferentIndex_ShouldProduceDifferentIds() {
        // act
        var id1 = Page.generateId("/test/file.md", 0);
        var id2 = Page.generateId("/test/file.md", 1);

        // assert
        assertThat(id1).isNotEqualTo(id2);
    }
}
