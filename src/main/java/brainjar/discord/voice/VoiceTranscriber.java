package brainjar.discord.voice;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.audio.AudioTranscriptionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VoiceTranscriber {

    private static final Logger log = LoggerFactory.getLogger(VoiceTranscriber.class);

    private final AudioTranscriptionModel model;
    private final int maxSizeBytes;
    private final double maxDurationSeconds;

    @Autowired
    public VoiceTranscriber(AudioTranscriptionModel model, VoiceProperties props) {
        this(model, props.maxSizeBytes(), props.maxDurationSeconds());
    }

    VoiceTranscriber(AudioTranscriptionModel model, int maxSizeBytes, double maxDurationSeconds) {
        this.model = model;
        this.maxSizeBytes = maxSizeBytes;
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public TranscriptionResult transcribe(byte[] audio, String mimeType, double durationSeconds) {
        if (audio == null || audio.length == 0) {
            return new Failed("empty audio");
        }
        if (audio.length > maxSizeBytes) {
            return new TooLarge("file too large (%d bytes, max %d)".formatted(audio.length, maxSizeBytes));
        }
        if (durationSeconds > maxDurationSeconds) {
            return new TooLong("duration too long (%.1fs, max %.1fs)".formatted(durationSeconds, maxDurationSeconds));
        }

        try {
            var request = AudioTranscriptionRequest.builder()
                    .audio(Audio.builder()
                            .binaryData(audio)
                            .mimeType(mimeType != null ? mimeType : "audio/ogg")
                            .build())
                    .temperature(0.0)
                    .build();
            var text = model.transcribe(request).text();
            if (text == null || text.isBlank()) {
                return new Blank();
            }
            return new Success(text.strip());
        } catch (RuntimeException e) {
            log.error("Transcription failed", e);
            return new Failed(e.getMessage());
        }
    }

    public sealed interface TranscriptionResult {}

    public record Success(String text) implements TranscriptionResult {}

    public record Blank() implements TranscriptionResult {}

    public record TooLarge(String reason) implements TranscriptionResult {}

    public record TooLong(String reason) implements TranscriptionResult {}

    public record Failed(String reason) implements TranscriptionResult {}
}
