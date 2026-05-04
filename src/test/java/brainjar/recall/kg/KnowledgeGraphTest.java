package brainjar.recall.kg;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphTest {

    private KnowledgeGraph kg;

    private void setup() {
        kg = new KnowledgeGraph("jdbc:sqlite::memory:");
    }

    @Test
    void addTriple_ShouldBeQueryable() {
        // arrange
        setup();

        // act
        kg.addTriple("Perry", "uses", "OpenAI", LocalDate.of(2026, 1, 1), 1.0, null);

        // assert
        var results = kg.query("Perry");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().predicate()).isEqualTo("uses");
        assertThat(results.getFirst().object()).isEqualTo("openai");
    }

    @Test
    void addTriple_ShouldCreateEntities() {
        // arrange
        setup();

        // act
        kg.addTriple("Perry", "uses", "OpenAI", null, 1.0, null);

        // assert
        var entities = kg.allEntities();
        assertThat(entities).hasSize(2);
        assertThat(entities).anyMatch(e -> e.name().equals("Perry"));
        assertThat(entities).anyMatch(e -> e.name().equals("OpenAI"));
    }

    @Test
    void queryAsOf_ShouldReturnOnlyValidTriples() {
        // arrange
        setup();
        kg.addTriple("Perry", "uses", "GPT-4", LocalDate.of(2025, 1, 1), 1.0, null);
        kg.invalidate("Perry", "uses", "GPT-4", LocalDate.of(2025, 12, 31));
        kg.addTriple("Perry", "uses", "GPT-5", LocalDate.of(2026, 1, 1), 1.0, null);

        // act
        var mid2025 = kg.queryAsOf("Perry", LocalDate.of(2025, 6, 15));
        var mid2026 = kg.queryAsOf("Perry", LocalDate.of(2026, 6, 15));

        // assert
        assertThat(mid2025).hasSize(1);
        assertThat(mid2025.getFirst().object()).isEqualTo("gpt_4");

        assertThat(mid2026).hasSize(1);
        assertThat(mid2026.getFirst().object()).isEqualTo("gpt_5");
    }

    @Test
    void invalidate_ShouldSetValidTo() {
        // arrange
        setup();
        kg.addTriple("Team", "works on", "ProjectX", LocalDate.of(2025, 1, 1), 1.0, null);

        // act
        kg.invalidate("Team", "works on", "ProjectX", LocalDate.of(2025, 12, 31));

        // assert
        var results = kg.query("Team");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().validTo()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void addTriple_WhenDuplicateOpen_ShouldDedup() {
        // arrange
        setup();

        // act
        var id1 = kg.addTriple("Perry", "uses", "Spring", null, 1.0, null);
        var id2 = kg.addTriple("Perry", "uses", "Spring", null, 1.0, null);

        // assert
        assertThat(id1).isEqualTo(id2);
        assertThat(kg.query("Perry")).hasSize(1);
    }

    @Test
    void entityNormalization_ShouldLowercaseAndReplaceSpaces() {
        // act & assert
        assertThat(Entity.normalizeId("Spring Boot")).isEqualTo("spring_boot");
        assertThat(Entity.normalizeId("O'Brien")).isEqualTo("obrien");
        assertThat(Entity.normalizeId("JAVA")).isEqualTo("java");
    }

    @Test
    void predicateNormalization_ShouldLowercaseAndReplaceSpaces() {
        // act & assert
        assertThat(Triple.normalizePredicate("works on")).isEqualTo("works_on");
        assertThat(Triple.normalizePredicate("USES")).isEqualTo("uses");
    }

    @Test
    void query_WhenEmpty_ShouldReturnEmptyList() {
        // arrange
        setup();

        // act
        var results = kg.query("NonExistent");

        // assert
        assertThat(results).isEmpty();
    }

    @Test
    void queryAsOf_WhenNoValidTriples_ShouldReturnEmptyList() {
        // arrange
        setup();
        kg.addTriple("Perry", "uses", "GPT-4", LocalDate.of(2025, 1, 1), 1.0, null);
        kg.invalidate("Perry", "uses", "GPT-4", LocalDate.of(2025, 6, 30));

        // act
        var results = kg.queryAsOf("Perry", LocalDate.of(2026, 1, 1));

        // assert
        assertThat(results).isEmpty();
    }

    @Test
    void allTriples_ShouldReturnEveryRow() {
        // arrange
        setup();
        kg.addTriple("Perry", "uses", "Java", null, 1.0, null);
        kg.addTriple("Perry", "knows", "Rolf", null, 0.9, "p_1");
        kg.addTriple("Rolf", "works at", "Circulor", LocalDate.of(2023, 1, 1), 1.0, "p_2");

        // act
        var all = kg.allTriples();

        // assert
        assertThat(all).hasSize(3);
        assertThat(all).extracting(Triple::predicate)
                .containsExactlyInAnyOrder("uses", "knows", "works_at");
    }

    @Test
    void addTriple_ShouldStoreConfidence() {
        // arrange
        setup();

        // act
        kg.addTriple("Perry", "knows", "Java", null, 0.85, "page_123");

        // assert
        var results = kg.query("Perry");
        assertThat(results.getFirst().confidence()).isEqualTo(0.85);
        assertThat(results.getFirst().sourcePageId()).isEqualTo("page_123");
    }
}
