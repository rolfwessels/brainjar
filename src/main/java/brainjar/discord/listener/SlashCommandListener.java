package brainjar.discord.listener;

import brainjar.discord.command.SlashCommand;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SlashCommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandListener.class);

    private final Map<String, SlashCommand> commands;

    public SlashCommandListener(List<SlashCommand> commands) {
        this.commands = commands.stream().collect(Collectors.toMap(SlashCommand::name, Function.identity()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        var command = commands.get(event.getName());
        if (command == null) {
            log.warn("No handler for slash command: /{}", event.getName());
            event.reply("Unknown command.").setEphemeral(true).queue();
            return;
        }
        command.handle(event);
    }
}
