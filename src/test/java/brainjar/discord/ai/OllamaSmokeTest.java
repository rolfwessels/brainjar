package brainjar.discord.ai;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Perry can reach the local Ollama instance and get a response
 * from gemma4:e4b. Tagged "ollama" so it is skipped in normal CI.
 *
 * Run manually:
 *   ./gradlew test --tests "brainjar.discord.ai.OllamaSmokeTest"
 */
@Tag("ollama")
class OllamaSmokeTest {

    private static final String OLLAMA_BASE_URL = "http://192.168.1.101:11434";
    private static final String MODEL = "gemma4:e4b";

    @Test
    void gemma4RespondsToHello() {
        var model = OllamaChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder())
                .baseUrl(OLLAMA_BASE_URL)
                .modelName(MODEL)
                .build();

        var response = model.chat("Hello!");

        assertThat(response).isNotBlank();
        System.out.println("Gemma4 said: " + response);
    }
}
