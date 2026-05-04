package brainjar.discord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void split_WhenShort_ShouldReturnSingleChunk() {
        var result = MessageSplitter.split("hello world");

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class,
                msgs -> assertThat(msgs.chunks()).containsExactly("hello world"));
    }

    @Test
    void split_WhenNull_ShouldReturnSingleEmptyChunk() {
        var result = MessageSplitter.split(null);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class,
                msgs -> assertThat(msgs.chunks()).containsExactly(""));
    }

    @Test
    void split_WhenStartsWithFileDirective_ShouldReturnFileAttachment() {
        var raw = "<!--file:spec.md-->\n# Spec\n\nContent here.";

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.FileAttachment.class, att -> {
            assertThat(att.filename()).isEqualTo("spec.md");
            assertThat(att.content()).isEqualTo("# Spec\n\nContent here.");
        });
    }

    @Test
    void split_WhenFileDirectiveHasNoExtension_ShouldAppendMd() {
        var result = MessageSplitter.split("<!--file:notes-->\nbody");

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.FileAttachment.class,
                att -> assertThat(att.filename()).isEqualTo("notes.md"));
    }

    @Test
    void split_WhenFileDirectiveHasUnsafeChars_ShouldSanitise() {
        var result = MessageSplitter.split("<!--file:../etc/passwd-->\nbody");

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.FileAttachment.class,
                att -> {
                    assertThat(att.filename()).doesNotContain("/").doesNotContain("\\");
                    assertThat(att.filename()).endsWith(".md");
                });
    }

    @Test
    void split_WhenContainsSplitMarkers_ShouldSplitOnThem() {
        var raw = makeLongParagraph(800) + "\n\n<!--split-->\n\n" + makeLongParagraph(800);

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class, msgs -> {
            assertThat(msgs.chunks()).hasSize(2);
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.SAFE_CHUNK_LENGTH);
            }
        });
    }

    @Test
    void split_WhenMarkerChunkStillTooLong_ShouldFallBackToBoundary() {
        var huge = makeLongParagraph(3000);
        var raw = huge + "\n\n<!--split-->\n\ntail";

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class, msgs -> {
            assertThat(msgs.chunks().size()).isGreaterThanOrEqualTo(3);
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.SAFE_CHUNK_LENGTH);
            }
        });
    }

    @Test
    void split_WhenNoMarkersAndOverAutoFileThreshold_ShouldReturnFile() {
        var raw = makeLongParagraph(MessageSplitter.AUTO_FILE_THRESHOLD + 500);

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.FileAttachment.class, att -> {
            assertThat(att.filename()).isEqualTo("response.md");
            assertThat(att.content()).isEqualTo(raw);
        });
    }

    @Test
    void split_WhenNoMarkersAndBetweenSafeAndFileThreshold_ShouldSplitOnParagraphs() {
        var para = makeLongParagraph(1500);
        var raw = para + "\n\n" + para;

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class, msgs -> {
            assertThat(msgs.chunks()).hasSizeGreaterThanOrEqualTo(2);
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.SAFE_CHUNK_LENGTH);
            }
            assertThat(String.join("", msgs.chunks()).replace(" ", ""))
                    .isEqualTo(raw.replace(" ", "").replace("\n", ""));
        });
    }

    @Test
    void split_WhenNoParagraphsButHasLines_ShouldSplitOnLines() {
        var sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("line number ").append(i).append(" with some filler words ".repeat(3)).append('\n');
        }
        var raw = sb.toString();

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class, msgs -> {
            assertThat(msgs.chunks().size()).isGreaterThanOrEqualTo(2);
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.SAFE_CHUNK_LENGTH);
            }
        });
    }

    @Test
    void split_WhenNoBoundariesAtAll_ShouldHardCut() {
        var raw = "x".repeat(3500);

        var result = MessageSplitter.split(raw);

        assertThat(result).isInstanceOfSatisfying(MessageSplitter.Messages.class, msgs -> {
            assertThat(msgs.chunks()).hasSize(2);
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.SAFE_CHUNK_LENGTH);
            }
        });
    }

    @Test
    void split_ShouldNeverProduceChunkOverDiscordLimit() {
        var raw = makeLongParagraph(1900 * 3);

        var result = MessageSplitter.split(raw);

        if (result instanceof MessageSplitter.Messages msgs) {
            for (var chunk : msgs.chunks()) {
                assertThat(chunk.length()).isLessThan(MessageSplitter.DISCORD_MAX_LENGTH);
            }
        }
    }

    private static String makeLongParagraph(int targetLength) {
        var word = "word ";
        var sb = new StringBuilder(targetLength + word.length());
        while (sb.length() < targetLength) {
            sb.append(word);
        }
        return sb.substring(0, targetLength);
    }
}
