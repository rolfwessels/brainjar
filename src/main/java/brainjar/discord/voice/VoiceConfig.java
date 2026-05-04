package brainjar.discord.voice;

import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VoiceProperties.class)
public class VoiceConfig {

    @Bean
    AudioTranscriptionModel audioTranscriptionModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            VoiceProperties props) {
        return OpenAiAudioTranscriptionModel.builder()
                .apiKey(apiKey)
                .modelName(props.transcriptionModel())
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
