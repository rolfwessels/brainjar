package brainjar.discord.ai;

import brainjar.recall.kg.KnowledgeGraph;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Durable {@link ChatMemoryStore} backed by the SQLite knowledge-graph database.
 * Serialises each user's rolling message window as a JSON blob in the
 * {@code chat_history} table so conversations survive process restarts.
 */
@Component
public class SqliteChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemoryStore.class);

    private final KnowledgeGraph knowledgeGraph;

    public SqliteChatMemoryStore(KnowledgeGraph knowledgeGraph) {
        this.knowledgeGraph = knowledgeGraph;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var json = knowledgeGraph.getChatMessagesJson(memoryId.toString());
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
        var json = ChatMessageSerializer.messagesToJson(messages);
        knowledgeGraph.updateChatMessagesJson(memoryId.toString(), json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        knowledgeGraph.deleteChatMessages(memoryId.toString());
    }
}
