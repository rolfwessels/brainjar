package brainjar.discord.command;

import brainjar.discord.ai.ChatMemoryRegistry;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ClearSessionCommand implements SlashCommand {

    public static final String NAME = "clear-session";
    public static final String DESCRIPTION = "Drop the current rolling chat window for this user";

    private static final Logger log = LoggerFactory.getLogger(ClearSessionCommand.class);

    private final ChatMemoryRegistry memoryRegistry;

    public ClearSessionCommand(ChatMemoryRegistry memoryRegistry) {
        this.memoryRegistry = memoryRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        var userId = event.getUser().getId();
        boolean cleared = memoryRegistry.clear(userId);
        var reply = cleared ? "Session cleared." : "No session to clear.";
        log.info("/clear-session invoked by {} — cleared={}", userId, cleared);
        event.reply(reply).setEphemeral(true).queue();
    }
}
