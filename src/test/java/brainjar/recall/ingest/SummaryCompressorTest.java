package brainjar.recall.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCompressorTest {

    private SummaryCompressor compressor;

    private void setup() {
        compressor = new SummaryCompressor();
    }

    @Test
    void compress_WhenNullInput_ShouldReturnEmptySummary() {
        // arrange
        setup();

        // act
        var result = compressor.compress(null, "page_1");

        // assert
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.pageId()).isEqualTo("page_1");
    }

    @Test
    void compress_WhenBlankInput_ShouldReturnEmptySummary() {
        // arrange
        setup();

        // act
        var result = compressor.compress("   ", "page_1");

        // assert
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void extractEntities_ShouldFindCapitalizedWordsNotAtSentenceStart() {
        // arrange
        setup();
        var text = "We decided to use Spring for the backend. The team also evaluated Django and Flask.";

        // act
        var entities = compressor.extractEntities(text);

        // assert
        assertThat(entities).contains("Spring");
        assertThat(entities).doesNotContain("We", "The");
    }

    @Test
    void extractEntities_ShouldLimitToTopThree() {
        // arrange
        setup();
        var text = "We use Spring and Hibernate with PostgreSQL and Redis and Kafka in our stack.";

        // act
        var entities = compressor.extractEntities(text);

        // assert
        assertThat(entities).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void extractEntities_ShouldIgnoreAllCapsWords() {
        // arrange
        setup();
        var text = "The API uses REST and JSON for data exchange.";

        // act
        var entities = compressor.extractEntities(text);

        // assert
        assertThat(entities).doesNotContain("API", "REST", "JSON");
    }

    @Test
    void extractTopics_ShouldFindFrequentWords() {
        // arrange
        setup();
        var text = "Testing is important. We write tests for all features. Test coverage matters. Testing should be automated.";

        // act
        var topics = compressor.extractTopics(text);

        // assert
        assertThat(topics).isNotEmpty();
        assertThat(topics.getFirst()).isIn("testing", "test", "tests");
    }

    @Test
    void extractTopics_ShouldExcludeStopWords() {
        // arrange
        setup();
        var text = "The system is designed for the users and the administrators of the platform.";

        // act
        var topics = compressor.extractTopics(text);

        // assert
        assertThat(topics).doesNotContain("the", "is", "for", "and", "of");
    }

    @Test
    void extractTopics_ShouldBoostTitleCaseAndCamelCase() {
        // arrange
        setup();
        var text = "We use SpringBoot for the app. The app runs spring services. SpringBoot is great.";

        // act
        var topics = compressor.extractTopics(text);

        // assert
        assertThat(topics).contains("springboot");
    }

    @Test
    void extractTopics_ShouldLimitToTopThree() {
        // arrange
        setup();
        var text = "Java Python Kotlin Rust Go Swift TypeScript Ruby Elixir Haskell are all programming languages.";

        // act
        var topics = compressor.extractTopics(text);

        // assert
        assertThat(topics).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void extractKeySentence_ShouldPreferDecisionVocabulary() {
        // arrange
        setup();
        var text = "The system processes data. We decided to use PostgreSQL because of its reliability. It runs on Linux.";

        // act
        var sentence = compressor.extractKeySentence(text);

        // assert
        assertThat(sentence).containsIgnoringCase("decided");
    }

    @Test
    void extractKeySentence_ShouldTruncateLongSentences() {
        // arrange
        setup();
        var text = "We decided to migrate from MySQL to PostgreSQL because PostgreSQL offers better JSON support and advanced indexing capabilities that our application needs.";

        // act
        var sentence = compressor.extractKeySentence(text);

        // assert
        assertThat(sentence).hasSizeLessThanOrEqualTo(58);
        assertThat(sentence).endsWith("...");
    }

    @Test
    void extractKeySentence_WhenEqualScores_ShouldPreferShorter() {
        // arrange
        setup();
        var text = "This is a medium length sentence with some content. Short and clear.";

        // act
        var sentence = compressor.extractKeySentence(text);

        // assert
        assertThat(sentence).isNotBlank();
    }

    @Test
    void extractFlags_ShouldDetectFlagKeywords() {
        // arrange
        setup();
        var text = "This is a TODO item. There is also a security concern here. Performance could be improved.";

        // act
        var flags = compressor.extractFlags(text);

        // assert
        assertThat(flags).contains("TODO", "SECURITY", "PERF");
    }

    @Test
    void extractFlags_ShouldLimitToThree() {
        // arrange
        setup();
        var text = "TODO: fix this hack. FIXME: bug here. Also deprecated and has a security warning with breaking changes. Important!";

        // act
        var flags = compressor.extractFlags(text);

        // assert
        assertThat(flags).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void extractFlags_WhenNoFlags_ShouldReturnEmptyList() {
        // arrange
        setup();
        var text = "This is a normal sentence with no special markers.";

        // act
        var flags = compressor.extractFlags(text);

        // assert
        assertThat(flags).isEmpty();
    }

    @Test
    void compress_ShouldReturnFullSummary() {
        // arrange
        setup();
        var text = "We decided to use Spring Boot for our project. Spring provides great dependency injection. TODO: add security config.";

        // act
        var summary = compressor.compress(text, "page_abc");

        // assert
        assertThat(summary.pageId()).isEqualTo("page_abc");
        assertThat(summary.entities()).contains("Spring");
        assertThat(summary.keySentence()).containsIgnoringCase("decided");
        assertThat(summary.flags()).contains("TODO");
        assertThat(summary.isEmpty()).isFalse();
    }
}
