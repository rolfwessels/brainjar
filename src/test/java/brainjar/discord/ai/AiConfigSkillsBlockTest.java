package brainjar.discord.ai;

import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import brainjar.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigSkillsBlockTest {

    @Test
    void buildSkillsBlock_WhenEmpty_ShouldReturnEmptyString() {
        var registry = registry(Map.of());
        assertThat(AiConfig.buildSkillsBlock(registry, "user-1")).isEmpty();
    }

    @Test
    void buildSkillsBlock_ShouldListNamesAndDescriptions() {
        var registry = registry(Map.of(
                "skills/alpha/SKILL.md", """
                        ---
                        name: alpha
                        description: alpha hint
                        ---
                        body
                        """,
                "skills/beta/SKILL.md", """
                        ---
                        name: beta
                        description: beta hint
                        ---
                        body
                        """
        ));

        var block = AiConfig.buildSkillsBlock(registry, "user-1");

        assertThat(block).contains("## Available skills");
        assertThat(block).contains("Call useSkill(name)");
        assertThat(block).contains("- alpha: alpha hint");
        assertThat(block).contains("- beta: beta hint");
    }

    @Test
    void buildSkillsBlock_ShouldTruncateAtCatalogueLimit() {
        var resources = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < AiConfig.SKILLS_CATALOGUE_MAX + 5; i++) {
            var name = "skill-" + String.format("%02d", i);
            resources.put("skills/" + name + "/SKILL.md", """
                    ---
                    name: %s
                    description: hint %d
                    ---
                    body
                    """.formatted(name, i));
        }
        var registry = registry(resources);

        var block = AiConfig.buildSkillsBlock(registry, "user-1");

        assertThat(block).contains("+5 more");
    }

    @Test
    void buildSkillsBlock_ShouldFallBackForBlankDescription() {
        var registry = registry(Map.of(
                "skills/no-desc/SKILL.md", """
                        ---
                        name: no-desc
                        description:
                        ---
                        body
                        """
        ));

        var block = AiConfig.buildSkillsBlock(registry, "user-1");
        assertThat(block).contains("- no-desc: (no description)");
    }

    private static SkillRegistry registry(Map<String, String> resources) {
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
                            return (Resource) new ByteArrayResource(bytes) {
                                @Override
                                public String getDescription() {
                                    return e.getKey();
                                }
                            };
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
