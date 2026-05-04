package brainjar.discord.listener;

import brainjar.discord.DiscordProperties;
import brainjar.discord.command.ClearSessionCommand;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadyListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ReadyListener.class);

    private final DiscordProperties properties;
    private final String openAiModel;

    public ReadyListener(DiscordProperties properties,
                         @Value("${langchain4j.open-ai.chat-model.model-name}") String openAiModel) {
        this.properties = properties;
        this.openAiModel = openAiModel;
    }

    @Override
    public void onReady(ReadyEvent event) {
        log.info("BrainJar bot ready — logged in as {}", event.getJDA().getSelfUser().getAsTag());
        log.info("LLM model: {}", openAiModel);
        registerCommands(event);
    }

    private void registerCommands(ReadyEvent event) {
        var commands = new SlashCommandData[]{
                Commands.slash("ping", "Check if the bot is alive"),
                Commands.slash(ClearSessionCommand.NAME, ClearSessionCommand.DESCRIPTION)
        };
        if (isGuildMode()) {
            var guild = event.getJDA().getGuildById(properties.guildId());
            if (guild == null) {
                log.warn("Guild {} not found — falling back to global command registration", properties.guildId());
                event.getJDA().updateCommands().addCommands(commands).queue();
                return;
            }
            guild.updateCommands().addCommands(commands).queue();
            log.info("Slash commands registered to guild {}", guild.getName());
        } else {
            event.getJDA().updateCommands().addCommands(commands).queue();
            log.info("Slash commands registered globally (propagation may take up to 1 hour)");
        }
    }

    private boolean isGuildMode() {
        return properties.guildId() != null && !properties.guildId().isBlank();
    }
}
