package brainjar.discord;

import brainjar.discord.command.PingCommand;
import brainjar.discord.command.SlashCommand;
import brainjar.discord.listener.SlashCommandListener;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SlashCommandListenerTest {

    private SlashCommandListener listener;

    private static SlashCommandInteractionEvent mockEvent(String commandName) {
        var event = mock(SlashCommandInteractionEvent.class);
        var replyAction = mock(ReplyCallbackAction.class);
        when(event.getName()).thenReturn(commandName);
        when(event.reply(anyString())).thenReturn(replyAction);
        when(replyAction.setEphemeral(anyBoolean())).thenReturn(replyAction);
        return event;
    }

    private void setup(List<SlashCommand> commands) {
        listener = new SlashCommandListener(commands);
    }

    @Test
    void onSlashCommand_WhenPing_ShouldReplyPong() {
        // arrange
        setup(List.of(new PingCommand()));
        var event = mockEvent("ping");

        // act
        listener.onSlashCommandInteraction(event);

        // assert
        verify(event).reply("Pong!");
    }

    @Test
    void onSlashCommand_WhenUnknown_ShouldReplyEphemeral() {
        // arrange
        setup(List.of(new PingCommand()));
        var event = mockEvent("unknown");
        var replyAction = mock(ReplyCallbackAction.class);
        when(event.reply("Unknown command.")).thenReturn(replyAction);
        when(replyAction.setEphemeral(true)).thenReturn(replyAction);

        // act
        listener.onSlashCommandInteraction(event);

        // assert
        verify(event).reply("Unknown command.");
        verify(replyAction).setEphemeral(true);
    }
}
