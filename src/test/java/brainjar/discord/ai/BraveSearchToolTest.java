package brainjar.discord.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BraveSearchToolTest {

    private final BraveSearchTool tool = new BraveSearchTool(
            new BraveProperties("dummy-key"),
            new ObjectMapper()
    );

    @Test
    void formatResults_WhenResultsPresent_ShouldIncludeTitleUrlDescription() throws Exception {
        var json = """
                {
                  "web": {
                    "results": [
                      {
                        "title": "Dune: Part Two",
                        "url": "https://example.com/dune-two",
                        "description": "The second part of the Dune saga."
                      },
                      {
                        "title": "Dune Official Site",
                        "url": "https://dune.movie",
                        "description": "Official site for the Dune films."
                      }
                    ]
                  }
                }
                """;

        var result = tool.formatResults(json);

        assertThat(result).contains("Dune: Part Two");
        assertThat(result).contains("https://example.com/dune-two");
        assertThat(result).contains("The second part of the Dune saga.");
        assertThat(result).contains("Dune Official Site");
    }

    @Test
    void formatResults_WhenNoResults_ShouldReturnFallback() throws Exception {
        var json = """
                {
                  "web": {
                    "results": []
                  }
                }
                """;

        assertThat(tool.formatResults(json)).isEqualTo("No results found.");
    }

    @Test
    void formatResults_WhenWebNodeMissing_ShouldReturnFallback() throws Exception {
        assertThat(tool.formatResults("{}")).isEqualTo("No results found.");
    }
}
