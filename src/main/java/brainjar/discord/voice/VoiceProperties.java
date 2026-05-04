package brainjar.discord.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("brainjar.voice")
public record VoiceProperties(
        String transcriptionModel,
        int maxSizeBytes,
        double maxDurationSeconds) {

    public VoiceProperties {
        if (transcriptionModel == null || transcriptionModel.isBlank()) {
            transcriptionModel = "gpt-4o-mini-transcribe";
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = 24 * 1024 * 1024;
        }
        if (maxDurationSeconds <= 0) {
            maxDurationSeconds = 300.0;
        }
    }
}
