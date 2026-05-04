package brainjar.discord.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoicePropertiesTest {

    @Test
    void defaults_WhenNullOrZero_ShouldFillDefaults() {
        var props = new VoiceProperties(null, 0, 0);

        assertThat(props.transcriptionModel()).isEqualTo("gpt-4o-mini-transcribe");
        assertThat(props.maxSizeBytes()).isEqualTo(24 * 1024 * 1024);
        assertThat(props.maxDurationSeconds()).isEqualTo(300.0);
    }

    @Test
    void defaults_WhenBlankModel_ShouldFillDefault() {
        var props = new VoiceProperties("  ", 100, 10);

        assertThat(props.transcriptionModel()).isEqualTo("gpt-4o-mini-transcribe");
        assertThat(props.maxSizeBytes()).isEqualTo(100);
        assertThat(props.maxDurationSeconds()).isEqualTo(10.0);
    }

    @Test
    void explicitValues_ShouldPassThrough() {
        var props = new VoiceProperties("whisper-1", 1000, 30);

        assertThat(props.transcriptionModel()).isEqualTo("whisper-1");
        assertThat(props.maxSizeBytes()).isEqualTo(1000);
        assertThat(props.maxDurationSeconds()).isEqualTo(30.0);
    }
}
