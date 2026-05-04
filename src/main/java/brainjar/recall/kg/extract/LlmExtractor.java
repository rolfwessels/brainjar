package brainjar.recall.kg.extract;

import brainjar.recall.ingest.SummaryCompressor;
import brainjar.recall.kg.Predicate;
import brainjar.recall.kg.Triple;
import brainjar.recall.model.Page;
import brainjar.recall.model.Summary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM-backed triple extractor. Prompts the configured {@link ChatModel}
 * with the closed predicate vocabulary and a JSON schema, parses the
 * response, and rejects anything outside the vocabulary. Always produces
 * a {@link Summary} via {@link SummaryCompressor} (falls back to summary-
 * only if the LLM returns nothing usable).
 */
public class LlmExtractor implements Extractor {

    static final String VERSION = "hybrid-v1";
    private static final Logger log = LoggerFactory.getLogger(LlmExtractor.class);
    private static final int MAX_CHARS = 3500;
    private static final double DEFAULT_CONFIDENCE = 0.7;

    private final ChatModel chatModel;
    private final SummaryCompressor summaryCompressor;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmExtractor(ChatModel chatModel) {
        this(chatModel, new SummaryCompressor());
    }

    public LlmExtractor(ChatModel chatModel, SummaryCompressor summaryCompressor) {
        this.chatModel = chatModel;
        this.summaryCompressor = summaryCompressor;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ExtractionResult extract(Page page) {
        var summary = summaryCompressor.compress(page.content(), page.id());
        var prompt = buildPrompt(page);
        String response;
        try {
            response = chatModel.chat(prompt);
        } catch (RuntimeException e) {
            log.warn("LLM extraction failed for page {} ({}): {}",
                    page.id(), e.getClass().getSimpleName(), e.getMessage());
            return ExtractionResult.summaryOnly(summary);
        }

        var triples = parseTriples(response, page);
        return new ExtractionResult(summary, triples);
    }

    private String buildPrompt(Page page) {
        var content = truncate(page.content(), MAX_CHARS);
        var vocab = renderVocabulary();
        return """
                You extract typed facts from a short text into a small, closed vocabulary.

                OUTPUT: strict JSON only. No prose, no markdown fences. An array of objects with exactly these keys:
                  - "subject": a short noun phrase (a person, thing, place, or concept)
                  - "predicate": one of the allowed predicates listed below — no others
                  - "object":  a short noun phrase
                  - "confidence": a number in [0, 1]

                RULES:
                  - Only assert facts explicitly supported by the text. If in doubt, leave it out.
                  - Prefer fewer high-quality facts over many weak ones.
                  - Subject and object should be specific entity names, not pronouns or generic descriptions.
                  - If nothing is worth extracting, return an empty array [].

                ALLOWED PREDICATES (any other predicate will be rejected):
                %s

                TEXT:
                %s

                JSON:
                """.formatted(vocab, content);
    }

    private String renderVocabulary() {
        var sb = new StringBuilder();
        for (var predicate : Predicate.vocabulary()) {
            var kind = Predicate.kindOf(predicate).orElseThrow();
            sb.append("  - ").append(predicate);
            if (kind == Predicate.Kind.FUNCTIONAL) {
                sb.append(" (functional: at most one true value per subject; newer facts replace older ones)");
            } else {
                sb.append(" (multi-valued: many values can coexist)");
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private List<Triple> parseTriples(String response, Page page) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        var json = extractJsonArray(response);
        if (json == null) {
            log.debug("No JSON array in LLM response for page {}: {}", page.id(), preview(response));
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            log.debug("Bad JSON from LLM for page {}: {}", page.id(), e.getMessage());
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }

        var validFrom = resolveValidFrom(page);
        var triples = new ArrayList<Triple>();
        int rejected = 0;
        for (var node : root) {
            var triple = toTriple(node, page, validFrom);
            if (triple == null) {
                rejected++;
                continue;
            }
            triples.add(triple);
        }
        if (rejected > 0) {
            log.info("LlmExtractor page={} kept={} rejected={}", page.id(), triples.size(), rejected);
        }
        return triples;
    }

    private Triple toTriple(JsonNode node, Page page, LocalDate validFrom) {
        if (!node.isObject()) {
            return null;
        }
        var subject = text(node, "subject");
        var predicate = text(node, "predicate");
        var object = text(node, "object");
        if (subject.isBlank() || predicate.isBlank() || object.isBlank()) {
            return null;
        }
        var normalizedPredicate = Predicate.normalize(predicate);
        if (!Predicate.isKnown(normalizedPredicate)) {
            log.debug("Rejecting unknown predicate \"{}\" on page {}", predicate, page.id());
            return null;
        }
        double confidence = DEFAULT_CONFIDENCE;
        if (node.hasNonNull("confidence") && node.get("confidence").isNumber()) {
            confidence = clamp(node.get("confidence").asDouble(), 0.0, 1.0);
        }
        return new Triple(null, subject, normalizedPredicate, object, validFrom, null,
                confidence, page.id());
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").strip();
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static LocalDate resolveValidFrom(Page page) {
        var lastModified = page.book().lastModified();
        return lastModified != null
                ? lastModified.atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.now();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String preview(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }
}
