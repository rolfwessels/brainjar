# Discord Bot Development in Java

Reference notes on modern Java Discord bot development (researched April 2026).

## Library Comparison

| Criteria | JDA | Discord4J | Javacord |
|---|---|---|---|
| GitHub Stars | ~4,600 | ~1,900 | ~770 |
| Latest Version | v6.3.2 (Mar 2026) | v3.3.1 (Feb 2026) | Stale |
| Programming Model | Imperative (callbacks/futures) | Reactive (Project Reactor) | Imperative |
| Spring Integration | Manual bean or starter (see below) | Natural fit with WebFlux | None |
| DAVE Protocol (voice E2E) | Yes (v6+, mandatory since Mar 2026) | Yes | Unknown |
| License | Apache 2.0 | LGPL v3 | Apache 2.0 |

**Recommendation: JDA 6.x.** Largest community, fastest feature adoption, imperative model fits Spring Boot well.

> IMPORTANT: JDA 5.x is incompatible with Discord since March 2026 — the DAVE voice encryption protocol is mandatory and only supported in JDA 6+.

## Spring Boot Integration

Do NOT use `spring-boot-starter-discord` (zgamelogic) — it is pinned to JDA 5.6.1, which is broken with current Discord.

Wire JDA manually as a Spring `@Bean`:

```java
@Configuration
public class DiscordBotConfig {

    @Bean
    JDA jda(DiscordProperties properties, List<ListenerAdapter> listeners) {
        return JDABuilder.createLight(properties.token(),
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(listeners.toArray())
            .build();
    }
}
```

Each listener is a `@Component extends ListenerAdapter`. Spring auto-discovers them via the `List<ListenerAdapter>` injection.

## Gateway Intents

Only request what you need — Discord audits privileged intents for bots in 100+ servers.

| Intent | Privileged | Purpose |
|---|---|---|
| `GUILD_MESSAGES` | No | Thread events, message events in guilds |
| `MESSAGE_CONTENT` | **Yes** | Read the actual text of messages (required for AI) |
| `DIRECT_MESSAGES` | No | DM support |
| `GUILD_MEMBERS` | **Yes** | Member join/leave events |

`MESSAGE_CONTENT` must be enabled in the Discord Developer Portal and requires approval for bots in 100+ servers.

## Slash Commands vs Message Commands

Prefer slash commands:
- No privileged intents required for the command trigger itself
- Built-in input validation and autocompletion
- Discoverable by users (shown in the `/` menu)
- Discord is deprecating message-based prefix commands

Register slash commands in `onReady()`:

```java
// Guild registration (instant, good for dev)
guild.upsertCommand(Commands.slash("ping", "Check if bot is alive")).queue();

// Global registration (up to 1 hour to propagate, use for production)
jda.updateCommands().addCommands(Commands.slash("ping", "Check if bot is alive")).queue();
```

Use `guild-id` config to switch between the two modes without code changes.

## Rate Limiting

JDA handles rate limits automatically by reading Discord's response headers. Rules:
- Use `queue()` (async, non-blocking) over `complete()` (blocks the thread)
- Never implement manual rate-limit logic — JDA queues requests internally
- Virtual threads (enabled in this project) make `complete()` safer but `queue()` is still preferred

## Interaction Components

Handle buttons, modals, and select menus via `ListenerAdapter`:

| Event | Handler Method |
|---|---|
| Button click | `onButtonInteraction(ButtonInteractionEvent e)` |
| Select menu | `onStringSelectInteraction(StringSelectInteractionEvent e)` |
| Modal submit | `onModalInteraction(ModalInteractionEvent e)` |
| Slash command | `onSlashCommandInteraction(SlashCommandInteractionEvent e)` |

Encode user IDs in component IDs to restrict interactions:

```java
Button.primary("confirm:" + userId, "Confirm")
```

## Token Security

- Store token in environment variable: `DISCORD_BOT_TOKEN`
- Reference in `application.yml`: `token: ${DISCORD_BOT_TOKEN}`
- Never hardcode or commit tokens
- Use `@ConfigurationProperties` record for type-safe config binding
- Minimise bot permissions — never use Administrator unless required

## Project Structure (this project)

```
src/main/java/brainjar/
  discord/
    DiscordBotConfig.java           -- @Configuration: JDA bean
    DiscordProperties.java          -- @ConfigurationProperties record
  discord/listener/
    ReadyListener.java              -- startup, slash command registration
    SlashCommandListener.java       -- routes slash commands to handlers
  discord/command/
    PingCommand.java                -- /ping health check
```

## Gradle Dependency

```kotlin
repositories {
    maven("https://jda.maven.dev/releases")
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:6.3.2")
}
```

## References

- [JDA GitHub](https://github.com/discord-jda/JDA)
- [JDA Documentation](https://docs.jda.wiki/)
- [Discord Developer Portal](https://discord.com/developers/applications)
