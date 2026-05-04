package brainjar.discord.command;

import brainjar.discord.ai.ChatMemoryRegistry;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ClearSessionCommandTest {

    private ChatMemoryRegistry registry;
    private ClearSessionCommand command;

    @BeforeEach
    void setup() {
        registry = new ChatMemoryRegistry(new InMemoryChatMemoryStore());
        command = new ClearSessionCommand(registry);
    }

    @Test
    void handle_WhenMemoryExists_ShouldReplyCleared() {
        registry.getOrCreate("user-1").add(UserMessage.from("remember this"));
        var event = mockEvent("user-1");

        command.handle(event);

        verify(event).reply("Session cleared.");
    }

    @Test
    void handle_WhenNoMemory_ShouldReplyNothingToClear() {
        var event = mockEvent("ghost");

        command.handle(event);

        verify(event).reply("No session to clear.");
    }

    @Test
    void handle_ShouldReplyEphemeral() {
        var event = mockEvent("user-1");

        command.handle(event);

        verify(event.reply(anyString())).setEphemeral(true);
    }

    private static SlashCommandInteractionEvent mockEvent(String userId) {
        var event = mock(SlashCommandInteractionEvent.class);
        var user = mock(User.class);
        var replyAction = mock(ReplyCallbackAction.class);
        when(user.getId()).thenReturn(userId);
        when(event.getUser()).thenReturn(user);
        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(anyBoolean())).thenReturn(replyAction);
        return event;
    }
}
