package brainjar.discord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPropertiesTest {

    @Test
    void guildMode_WhenGuildIdSet_ShouldBeNonBlank() {
        // arrange
        var properties = new DiscordProperties("fake-token", "123456789");

        // act & assert
        assertThat(properties.guildId()).isNotBlank();
    }

    @Test
    void guildMode_WhenGuildIdEmpty_ShouldBeBlank() {
        // arrange
        var properties = new DiscordProperties("fake-token", "");

        // act & assert
        assertThat(properties.guildId()).isBlank();
    }

    @Test
    void properties_ShouldExposeToken() {
        // arrange
        var properties = new DiscordProperties("my-token", null);

        // act & assert
        assertThat(properties.token()).isEqualTo("my-token");
    }
}
