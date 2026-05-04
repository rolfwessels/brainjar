package brainjar.recall.kg.extract;

import brainjar.recall.model.Summary;

/**
 * Cheap heuristic score deciding whether a page deserves the LLM extraction
 * pass. Reuses the fields already computed by
 * {@link brainjar.recall.ingest.SummaryCompressor} so we don't touch the
 * page text a second time.
 *
 * <p>Scoring is intentionally coarse: the goal is "probably worth an LLM
 * call" vs "almost certainly not", not a precision weapon. Tune the
 * threshold with real corpus data.
 */
public final class ExtractorSignals {

    /**
     * Default cut-off for calling the LLM. Pages scoring at or above this
     * get the full typed-triple treatment; pages below it stop at the
     * summary.
     */
    public static final int DEFAULT_THRESHOLD = 3;

    private ExtractorSignals() {}

    public static int score(Summary summary, String content) {
        if (summary == null) {
            return 0;
        }
        int score = 0;
        score += Math.min(summary.entities().size(), 3);
        score += Math.min(summary.flags().size(), 2);
        if (summary.keySentence() != null && !summary.keySentence().isBlank()) {
            score += 1;
        }
        if (content != null) {
            score += Math.min(countDeclarativeSentences(content), 3);
        }
        return score;
    }

    public static boolean worthExtracting(Summary summary, String content) {
        return score(summary, content) >= DEFAULT_THRESHOLD;
    }

    private static int countDeclarativeSentences(String content) {
        int count = 0;
        int length = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                if (length >= 20 && length <= 200) {
                    count++;
                }
                length = 0;
            } else {
                length++;
            }
        }
        return count;
    }
}
