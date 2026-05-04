package brainjar.discord.ai;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of per-user chat memories, backing the LangChain4j
 * {@code ChatMemoryProvider}. We hold the map ourselves (rather than
 * letting {@code AiServices} own it internally) so that slash commands
 * like {@code /clear-session} can drop an individual user's rolling
 * window on demand.
 *
 * <p>Each {@link MessageWindowChatMemory} is backed by a
 * {@link SqliteChatMemoryStore} so the window survives process restarts.
 */
@Component
public class ChatMemoryRegistry {

    private static final int MAX_MESSAGES = 20;

    private final ConcurrentMap<Object, ChatMemory> memories = new ConcurrentHashMap<>();
    private final ChatMemoryStore store;

    public ChatMemoryRegistry(ChatMemoryStore store) {
        this.store = store;
    }

    public ChatMemory getOrCreate(Object memoryId) {
        return memories.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(MAX_MESSAGES)
                        .chatMemoryStore(store)
                        .build());
    }

    /**
     * Clears the memory for the given id from both the in-process cache and
     * the durable store. Returns {@code true} if a memory was present,
     * {@code false} if there was nothing to clear.
     */
    public boolean clear(Object memoryId) {
        var memory = memories.remove(memoryId);
        if (memory == null) {
            store.deleteMessages(memoryId);
            return false;
        }
        memory.clear();
        return true;
    }
}
