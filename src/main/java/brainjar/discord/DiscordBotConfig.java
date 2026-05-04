package brainjar.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(DiscordProperties.class)
public class DiscordBotConfig {

    @Bean
    JDA jda(DiscordProperties properties, List<ListenerAdapter> listeners) throws InterruptedException {
        return JDABuilder.createLight(properties.token(),
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.DIRECT_MESSAGES)
            .addEventListeners(listeners.toArray())
            .build()
            .awaitReady();
    }
}
