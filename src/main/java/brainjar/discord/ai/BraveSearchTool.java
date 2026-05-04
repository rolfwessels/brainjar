package brainjar.discord.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Component
public class BraveSearchTool {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchTool.class);
    private static final String SEARCH_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int RESULT_COUNT = 5;

    private final BraveProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BraveSearchTool(BraveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Tool("Search the web for current or real-time information. Use when asked about recent events, news, facts you are unsure about, or anything requiring up-to-date data.")
    public String searchWeb(String query) {
        log.info("Web search: \"{}\"", query);
        try {
            var url = SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=" + RESULT_COUNT;
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", properties.apiKey())
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Brave API returned status {}: {}", response.statusCode(), response.body());
                return "Web search unavailable (status " + response.statusCode() + ").";
            }
            log.debug("Brave API responded with status {}", response.statusCode());
            return formatResults(response.body());
        } catch (Exception e) {
            log.error("Web search failed for query \"{}\": {}", query, e.getMessage());
            return "Web search failed: " + e.getMessage();
        }
    }

    String formatResults(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path("web").path("results");

        if (!results.isArray() || results.isEmpty()) {
            return "No results found.";
        }

        var sb = new StringBuilder();
        for (JsonNode result : results) {
            sb.append(result.path("title").asText()).append("\n");
            sb.append(result.path("url").asText()).append("\n");
            sb.append(result.path("description").asText()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
