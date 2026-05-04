package brainjar.discord.voice;

import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.audio.AudioTranscriptionRequest;
import dev.langchain4j.model.audio.AudioTranscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTranscriberTest {

    private AudioTranscriptionModel model;
    private VoiceTranscriber transcriber;

    @BeforeEach
    void setup() {
        model = mock(AudioTranscriptionModel.class);
        transcriber = new VoiceTranscriber(model, 10_000, 60.0);
    }

    @Test
    void transcribe_WhenValid_ShouldReturnSuccessWithText() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenReturn(AudioTranscriptionResponse.from("hello world"));

        var result = transcriber.transcribe(new byte[]{1, 2, 3}, "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.Success.class);
        assertThat(((VoiceTranscriber.Success) result).text()).isEqualTo("hello world");
    }

    @Test
    void transcribe_ShouldStripWhitespaceFromText() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenReturn(AudioTranscriptionResponse.from("  hello  "));

        var result = transcriber.transcribe(new byte[]{1, 2, 3}, "audio/ogg", 5.0);

        assertThat(((VoiceTranscriber.Success) result).text()).isEqualTo("hello");
    }

    @Test
    void transcribe_WhenBytesExceedMaxSize_ShouldReturnTooLarge() {
        var oversized = new byte[10_001];

        var result = transcriber.transcribe(oversized, "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.TooLarge.class);
        verify(model, never()).transcribe(any(AudioTranscriptionRequest.class));
    }

    @Test
    void transcribe_WhenDurationExceedsMax_ShouldReturnTooLong() {
        var result = transcriber.transcribe(new byte[]{1, 2, 3}, "audio/ogg", 60.1);

        assertThat(result).isInstanceOf(VoiceTranscriber.TooLong.class);
        verify(model, never()).transcribe(any(AudioTranscriptionRequest.class));
    }

    @Test
    void transcribe_WhenModelReturnsBlank_ShouldReturnBlank() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenReturn(AudioTranscriptionResponse.from("   "));

        var result = transcriber.transcribe(new byte[]{1, 2, 3}, "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.Blank.class);
    }

    @Test
    void transcribe_WhenBytesNull_ShouldReturnFailed() {
        var result = transcriber.transcribe(null, "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.Failed.class);
        verify(model, never()).transcribe(any(AudioTranscriptionRequest.class));
    }

    @Test
    void transcribe_WhenBytesEmpty_ShouldReturnFailed() {
        var result = transcriber.transcribe(new byte[0], "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.Failed.class);
        verify(model, never()).transcribe(any(AudioTranscriptionRequest.class));
    }

    @Test
    void transcribe_WhenModelThrows_ShouldReturnFailed() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenThrow(new RuntimeException("boom"));

        var result = transcriber.transcribe(new byte[]{1, 2, 3}, "audio/ogg", 5.0);

        assertThat(result).isInstanceOf(VoiceTranscriber.Failed.class);
        assertThat(((VoiceTranscriber.Failed) result).reason()).isEqualTo("boom");
    }

    @Test
    void transcribe_ShouldPassAudioAndMimeTypeToModel() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenReturn(AudioTranscriptionResponse.from("ok"));
        var captor = ArgumentCaptor.forClass(AudioTranscriptionRequest.class);
        var bytes = new byte[]{9, 8, 7};

        transcriber.transcribe(bytes, "audio/ogg", 5.0);

        verify(model).transcribe(captor.capture());
        var request = captor.getValue();
        assertThat(request.audio().binaryData()).isEqualTo(bytes);
        assertThat(request.audio().mimeType()).isEqualTo("audio/ogg");
        assertThat(request.temperature()).isEqualTo(0.0);
    }

    @Test
    void transcribe_WhenMimeTypeNull_ShouldDefaultToOgg() {
        when(model.transcribe(any(AudioTranscriptionRequest.class)))
                .thenReturn(AudioTranscriptionResponse.from("ok"));
        var captor = ArgumentCaptor.forClass(AudioTranscriptionRequest.class);

        transcriber.transcribe(new byte[]{1}, null, 5.0);

        verify(model).transcribe(captor.capture());
        assertThat(captor.getValue().audio().mimeType()).isEqualTo("audio/ogg");
    }
}
