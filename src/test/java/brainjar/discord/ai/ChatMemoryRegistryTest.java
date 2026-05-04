package brainjar.discord.ai;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryRegistryTest {

    private static ChatMemoryRegistry registry() {
        return new ChatMemoryRegistry(new InMemoryChatMemoryStore());
    }

    @Test
    void getOrCreate_ShouldReturnSameMemoryForSameId() {
        var registry = registry();

        var first = registry.getOrCreate("user-1");
        var second = registry.getOrCreate("user-1");

        assertThat(first).isSameAs(second);
    }

    @Test
    void getOrCreate_ShouldReturnDifferentMemoriesForDifferentIds() {
        var registry = registry();

        var a = registry.getOrCreate("user-1");
        var b = registry.getOrCreate("user-2");

        assertThat(a).isNotSameAs(b);
    }

    @Test
    void clear_WhenMemoryExists_ShouldReturnTrueAndDropMessages() {
        var registry = registry();
        var memory = registry.getOrCreate("user-1");
        memory.add(UserMessage.from("hello"));

        boolean cleared = registry.clear("user-1");

        assertThat(cleared).isTrue();
        assertThat(registry.getOrCreate("user-1").messages()).isEmpty();
    }

    @Test
    void clear_WhenMemoryAbsent_ShouldReturnFalse() {
        var registry = registry();

        assertThat(registry.clear("ghost")).isFalse();
    }

    @Test
    void clear_ShouldOnlyAffectTargetMemory() {
        var registry = registry();
        registry.getOrCreate("user-1").add(UserMessage.from("keep me"));
        registry.getOrCreate("user-2").add(UserMessage.from("drop me"));

        registry.clear("user-2");

        assertThat(registry.getOrCreate("user-1").messages()).hasSize(1);
        assertThat(registry.getOrCreate("user-2").messages()).isEmpty();
    }
}
