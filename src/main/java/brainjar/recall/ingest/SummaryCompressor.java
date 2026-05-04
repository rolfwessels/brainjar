package brainjar.recall.ingest;

import brainjar.recall.model.Summary;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SummaryCompressor {

    private static final int MAX_ENTITIES = 3;
    private static final int MAX_TOPICS = 3;
    private static final int MAX_FLAGS = 3;
    private static final int KEY_SENTENCE_MAX_LENGTH = 55;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "and", "but", "or",
            "nor", "not", "so", "yet", "for", "of", "in", "on", "at", "to", "by",
            "up", "with", "from", "into", "about", "as", "it", "its", "this",
            "that", "these", "those", "he", "she", "they", "we", "you", "i",
            "me", "my", "your", "his", "her", "our", "their", "what", "which",
            "who", "whom", "when", "where", "why", "how", "all", "each", "every",
            "both", "few", "more", "most", "other", "some", "such", "no", "only",
            "same", "than", "too", "very", "just", "also", "if", "then", "else",
            "while", "because", "though", "although", "after", "before", "since",
            "until", "unless", "between", "through", "during", "above", "below"
    );

    private static final Set<String> DECISION_WORDS = Set.of(
            "decided", "chose", "selected", "picked", "switched", "migrated",
            "adopted", "rejected", "dropped", "replaced", "prefer", "chosen",
            "decision", "choice", "because", "reason", "why", "instead",
            "rather", "over", "versus", "vs", "tradeoff", "trade-off"
    );

    private static final Map<String, String> FLAG_SIGNALS = Map.of(
            "todo", "TODO",
            "fixme", "FIXME",
            "hack", "HACK",
            "bug", "BUG",
            "deprecated", "DEPRECATED",
            "breaking", "BREAKING",
            "security", "SECURITY",
            "performance", "PERF",
            "important", "IMPORTANT",
            "warning", "WARNING"
    );

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?\\n]+");
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,;:()\\[\\]{}\"]+");

    public Summary compress(String text, String pageId) {
        if (text == null || text.isBlank()) {
            return new Summary(pageId, List.of(), List.of(), "", List.of());
        }

        return new Summary(
                pageId,
                extractEntities(text),
                extractTopics(text),
                extractKeySentence(text),
                extractFlags(text)
        );
    }

    List<String> extractEntities(String text) {
        var sentences = SENTENCE_SPLIT.split(text);
        var entityCounts = new HashMap<String, Integer>();

        for (var sentence : sentences) {
            var trimmed = sentence.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            var words = WORD_SPLIT.split(trimmed);
            for (int i = 0; i < words.length; i++) {
                var word = words[i];
                if (isEntity(word, i)) {
                    entityCounts.merge(word, 1, Integer::sum);
                }
            }
        }

        return entityCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_ENTITIES)
                .map(Map.Entry::getKey)
                .toList();
    }

    List<String> extractTopics(String text) {
        var words = WORD_SPLIT.split(text.toLowerCase());
        var wordCounts = new HashMap<String, Integer>();

        for (var word : words) {
            if (word.length() < 3 || STOP_WORDS.contains(word)) {
                continue;
            }
            wordCounts.merge(word, 1, Integer::sum);
        }

        boostSpecialCasing(text, wordCounts);

        return wordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_TOPICS)
                .map(Map.Entry::getKey)
                .toList();
    }

    String extractKeySentence(String text) {
        var sentences = SENTENCE_SPLIT.split(text);
        String best = "";
        int bestScore = -1;

        for (var sentence : sentences) {
            var trimmed = sentence.strip();
            if (trimmed.length() < 10) {
                continue;
            }
            int score = scoreSentence(trimmed);
            if (score > bestScore || (score == bestScore && trimmed.length() < best.length())) {
                best = trimmed;
                bestScore = score;
            }
        }

        if (best.length() > KEY_SENTENCE_MAX_LENGTH) {
            return best.substring(0, KEY_SENTENCE_MAX_LENGTH).strip() + "...";
        }
        return best;
    }

    List<String> extractFlags(String text) {
        var lower = text.toLowerCase();
        return FLAG_SIGNALS.entrySet().stream()
                .filter(entry -> lower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted()
                .limit(MAX_FLAGS)
                .toList();
    }

    private boolean isEntity(String word, int positionInSentence) {
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
            return false;
        }
        if (positionInSentence == 0) {
            return false;
        }
        if (word.length() < 2 || word.equals(word.toUpperCase())) {
            return false;
        }
        return !STOP_WORDS.contains(word.toLowerCase());
    }

    private void boostSpecialCasing(String text, Map<String, Integer> wordCounts) {
        var originalWords = WORD_SPLIT.split(text);
        for (var word : originalWords) {
            if (isTitleCase(word) || isCamelCase(word) || word.contains("_") || word.contains("-")) {
                var lower = word.toLowerCase();
                if (wordCounts.containsKey(lower)) {
                    wordCounts.merge(lower, 2, Integer::sum);
                }
            }
        }
    }

    private int scoreSentence(String sentence) {
        var words = WORD_SPLIT.split(sentence.toLowerCase());
        int score = 0;
        for (var word : words) {
            if (DECISION_WORDS.contains(word)) {
                score += 2;
            }
        }
        if (sentence.length() < 100) {
            score += 1;
        }
        return score;
    }

    private boolean isTitleCase(String word) {
        return word.length() > 1
                && Character.isUpperCase(word.charAt(0))
                && word.substring(1).chars().allMatch(Character::isLowerCase);
    }

    private boolean isCamelCase(String word) {
        if (word.length() < 3) {
            return false;
        }
        boolean hasLower = false;
        boolean hasUpperAfterFirst = false;
        for (int i = 1; i < word.length(); i++) {
            if (Character.isLowerCase(word.charAt(i))) {
                hasLower = true;
            }
            if (Character.isUpperCase(word.charAt(i))) {
                hasUpperAfterFirst = true;
            }
        }
        return hasLower && hasUpperAfterFirst;
    }
}
