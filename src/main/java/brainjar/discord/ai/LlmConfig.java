package brainjar.discord.ai;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /**
     * When OLLAMA_BASE_URL is set, this bean is registered and the OpenAI
     * autoconfiguration's @ConditionalOnMissingBean(ChatModel.class) backs off.
     */
    @Bean
    @Primary
    @ConditionalOnProperty("ollama.base-url")
    ChatModel ollamaChatModel(
            HttpClientBuilder httpClientBuilder,
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.model-name}") String modelName,
            @Value("${ollama.timeout-seconds:120}") int timeoutSeconds,
            @Value("${ollama.num-ctx:32768}") int numCtx) {
        return OllamaChatModel.builder()
                .httpClientBuilder(httpClientBuilder)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .numCtx(numCtx)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> llmStartupLog(
            ChatModel chatModel,
            @Value("${ollama.base-url:}") String ollamaBaseUrl,
            @Value("${ollama.model-name:}") String ollamaModel,
            @Value("${langchain4j.open-ai.chat-model.model-name:}") String openAiModel) {
        return event -> {
            if (!ollamaBaseUrl.isBlank()) {
                log.info("LLM provider: Ollama — model={} url={}", ollamaModel, ollamaBaseUrl);
            } else {
                log.info("LLM provider: OpenAI — model={}", openAiModel);
            }
            log.debug("ChatModel impl: {}", chatModel.getClass().getSimpleName());
        };
    }
}
