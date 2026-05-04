package brainjar.discord.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "brave")
public record BraveProperties(String apiKey) {}
