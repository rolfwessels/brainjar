package brainjar.discord.ai;

import brainjar.recall.store.SqliteChatHistoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SqliteChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemoryStore.class);

    private final SqliteChatHistoryStore store;

    public SqliteChatMemoryStore(SqliteChatHistoryStore store) {
        this.store = store;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var json = store.getMessagesJson(memoryId.toString());
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            log.warn("Failed to deserialise chat history for memoryId={}, returning empty: {}",
                    memoryId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.updateMessagesJson(memoryId.toString(), ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.deleteMessages(memoryId.toString());
    }
}
