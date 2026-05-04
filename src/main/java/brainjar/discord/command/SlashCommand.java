package brainjar.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public interface SlashCommand {
    String name();
    void handle(SlashCommandInteractionEvent event);
}
