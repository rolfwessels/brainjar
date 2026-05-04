package brainjar.skill;

import brainjar.context.UserContext;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolTest {

    private static final String USER_ID = "user-42";

    private InMemoryPageStore pageStore;
    private SkillRegistry registry;
    private SkillTool tool;

    @BeforeEach
    void setUp() {
        pageStore = new InMemoryPageStore(new FakeEmbeddingModel());
        registry = new SkillRegistry(pageStore, fakeResolver(Map.of(
                "skills/built-in-one/SKILL.md", """
                        ---
                        name: built-in-one
                        description: a built-in playbook
                        ---

                        # Built-in body
                        steps...
                        """
        )));
        tool = new SkillTool(registry);
        UserContext.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void useSkill_ShouldReturnBuiltInBody() {
        var body = tool.useSkill("built-in-one");
        assertThat(body).contains("# Built-in body").contains("steps...");
    }

    @Test
    void useSkill_WhenUnknown_ShouldReturnFriendlyMessage() {
        var response = tool.useSkill("does-not-exist");
        assertThat(response).containsIgnoringCase("no skill");
    }

    @Test
    void useSkill_WhenBlank_ShouldReturnFriendlyMessage() {
        assertThat(tool.useSkill("")).containsIgnoringCase("no skill");
        assertThat(tool.useSkill(null)).containsIgnoringCase("no skill");
    }

    @Test
    void teachSkill_ShouldPersistAndBeRetrievableViaUseSkill() {
        var teachResponse = tool.teachSkill("morning routine",
                "fire the 09:00 morning checklist",
                "1. coffee\n2. todo list\n3. block 90m for deep work");

        assertThat(teachResponse).contains("morning-routine");
        assertThat(pageStore.size()).isEqualTo(1);

        var body = tool.useSkill("morning-routine");
        assertThat(body).contains("name: morning-routine");
        assertThat(body).contains("1. coffee");
    }

    @Test
    void teachSkill_ShouldOverwriteSameNameInPlace() {
        tool.teachSkill("morning-routine", "v1", "first body");
        tool.teachSkill("morning-routine", "v2", "second body");

        assertThat(pageStore.size()).isEqualTo(1);
        var body = tool.useSkill("morning-routine");
        assertThat(body).contains("second body").doesNotContain("first body");
    }

    @Test
    void teachSkill_WhenBlankName_ShouldRefuse() {
        var response = tool.teachSkill("", "desc", "body");
        assertThat(response).containsIgnoringCase("name");
        assertThat(pageStore.size()).isZero();
    }

    @Test
    void useSkill_ShouldNotLeakOtherUsersTaughtSkills() {
        tool.teachSkill("only-mine", "scoped to me", "body");

        UserContext.set("other-user");
        var response = tool.useSkill("only-mine");
        assertThat(response).containsIgnoringCase("no skill");
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
