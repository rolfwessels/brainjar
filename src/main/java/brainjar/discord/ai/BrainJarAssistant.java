package brainjar.discord.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface BrainJarAssistant {

    String chat(@MemoryId String userId, @UserMessage String message);
}
