package brainjar.skill;

import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {

    @Test
    void parseFrontmatter_ShouldHandleInlineKeys() {
        var text = """
                ---
                name: foo
                description: a short hint
                ---

                body here
                """;
        var fm = SkillRegistry.parseFrontmatter(text);
        assertThat(fm).containsEntry("name", "foo");
        assertThat(fm).containsEntry("description", "a short hint");
    }

    @Test
    void parseFrontmatter_ShouldHandleFoldedScalar() {
        var text = """
                ---
                name: batch-cleanup
                description: >-
                  Process a large set of items gradually using a temp shelf
                  and a self-cancelling cron.
                ---

                body
                """;
        var fm = SkillRegistry.parseFrontmatter(text);
        assertThat(fm).containsEntry("name", "batch-cleanup");
        assertThat(fm.get("description")).contains("Process a large set of items")
                .contains("self-cancelling cron");
    }

    @Test
    void parseFrontmatter_WhenMissing_ShouldReturnEmpty() {
        assertThat(SkillRegistry.parseFrontmatter("# Just a markdown file")).isEmpty();
        assertThat(SkillRegistry.parseFrontmatter("")).isEmpty();
    }

    @Test
    void parseSkill_ShouldRejectMissingName() {
        var text = """
                ---
                description: only desc
                ---
                body
                """;
        assertThatThrownBy(() -> SkillRegistry.parseSkill(text, SkillDescriptor.Origin.BUILT_IN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("name");
    }

    @Test
    void slug_ShouldNormaliseLikeShelfLabels() {
        assertThat(SkillRegistry.slug("Batch Cleanup")).isEqualTo("batch-cleanup");
        assertThat(SkillRegistry.slug("  Morning Routine!! ")).isEqualTo("morning-routine");
        assertThat(SkillRegistry.slug("UPPER_case")).isEqualTo("upper-case");
    }

    @Test
    void load_ShouldDiscoverBuiltInSkillsFromClasspath() {
        var registry = realRegistry();
        var skills = registry.list("user-1");

        assertThat(skills).extracting(SkillDescriptor::name).contains("batch-cleanup");
        var batchCleanup = registry.find("user-1", "batch-cleanup").orElseThrow();
        assertThat(batchCleanup.origin()).isEqualTo(SkillDescriptor.Origin.BUILT_IN);
        assertThat(batchCleanup.description()).contains("temporary shelf");
        assertThat(batchCleanup.body()).contains("# Skill: batch-cleanup");
    }

    @Test
    void load_ShouldSkipMalformedBuiltInSkillsButKeepValidOnes() {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("skills/good/SKILL.md", """
                ---
                name: good
                description: works
                ---
                body
                """);
        resources.put("skills/bad/SKILL.md", """
                ---
                description: missing name
                ---
                body
                """);
        var registry = registryWithResources(resources);

        var skills = registry.list("user-1");
        assertThat(skills).extracting(SkillDescriptor::name).containsExactly("good");
    }

    @Test
    void teach_ShouldStoreUserSkillRetrievableByFind() {
        var registry = registryWithResources(Map.of());

        registry.teach("user-1", "Morning Routine",
                "fire 09:00 routine", "1. coffee\n2. todo list");

        var found = registry.find("user-1", "morning-routine").orElseThrow();
        assertThat(found.origin()).isEqualTo(SkillDescriptor.Origin.USER);
        assertThat(found.name()).isEqualTo("morning-routine");
        assertThat(found.description()).isEqualTo("fire 09:00 routine");
        assertThat(found.body()).contains("1. coffee").contains("2. todo list");
    }

    @Test
    void teach_TwiceWithSameName_ShouldOverwrite() {
        var registry = registryWithResources(Map.of());

        registry.teach("user-1", "morning-routine", "v1", "first body");
        registry.teach("user-1", "morning-routine", "v2", "second body");

        var found = registry.find("user-1", "morning-routine").orElseThrow();
        assertThat(found.description()).isEqualTo("v2");
        assertThat(found.body()).contains("second body").doesNotContain("first body");
    }

    @Test
    void list_ShouldMergeBuiltInsAndUserSkillsSortedByName() {
        Map<String, String> resources = Map.of("skills/zeta/SKILL.md", """
                ---
                name: zeta
                description: zeta hint
                ---
                """);
        var registry = registryWithResources(resources);
        registry.teach("user-1", "alpha", "alpha hint", "body");

        var skills = registry.list("user-1");
        assertThat(skills).extracting(SkillDescriptor::name).containsExactly("alpha", "zeta");
    }

    @Test
    void list_BuiltInShouldShadowUserTaughtOnNameCollision() {
        Map<String, String> resources = Map.of("skills/clash/SKILL.md", """
                ---
                name: clash
                description: built-in version
                ---
                BUILTIN BODY
                """);
        var registry = registryWithResources(resources);
        registry.teach("user-1", "clash", "user version", "USER BODY");

        var found = registry.find("user-1", "clash").orElseThrow();
        assertThat(found.origin()).isEqualTo(SkillDescriptor.Origin.BUILT_IN);
        assertThat(found.body()).contains("BUILTIN BODY");

        var listed = registry.list("user-1").stream()
                .filter(s -> s.name().equals("clash"))
                .toList();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).origin()).isEqualTo(SkillDescriptor.Origin.BUILT_IN);
    }

    @Test
    void list_ShouldNotLeakOtherUsersSkills() {
        var registry = registryWithResources(Map.of());

        registry.teach("user-1", "personal-one", "mine", "body");
        registry.teach("user-2", "personal-two", "theirs", "body");

        assertThat(registry.list("user-1")).extracting(SkillDescriptor::name)
                .containsExactly("personal-one");
        assertThat(registry.list("user-2")).extracting(SkillDescriptor::name)
                .containsExactly("personal-two");
    }

    @Test
    void find_WhenUnknownName_ShouldReturnEmpty() {
        var registry = registryWithResources(Map.of());
        assertThat(registry.find("user-1", "nope")).isEmpty();
        assertThat(registry.find("user-1", "")).isEmpty();
        assertThat(registry.find("user-1", null)).isEmpty();
    }

    private static SkillRegistry realRegistry() {
        var pageStore = new InMemoryPageStore(new FakeEmbeddingModel());
        return new SkillRegistry(pageStore);
    }

    private static SkillRegistry registryWithResources(Map<String, String> resources) {
        var pageStore = new InMemoryPageStore(new FakeEmbeddingModel());
        return new SkillRegistry(pageStore, fakeResolver(resources));
    }

    private static ResourcePatternResolver fakeResolver(Map<String, String> resources) {
        return new ResourcePatternResolver() {
            @Override
            public Resource[] getResources(String locationPattern) {
                return resources.entrySet().stream()
                        .map(e -> {
                            var bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
                            var resource = new ByteArrayResource(bytes) {
                                @Override
                                public String getDescription() {
                                    return e.getKey();
                                }
                            };
                            return (Resource) resource;
                        })
                        .toArray(Resource[]::new);
            }

            @Override
            public Resource getResource(String location) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

}
