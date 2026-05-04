package brainjar.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.springframework.stereotype.Component;

@Component
public class PingCommand implements SlashCommand {

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        event.reply("Pong!").queue();
    }
}
