package brainjar.recall.search;

import brainjar.recall.model.Page;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BM25-like keyword scorer over a snapshot of a {@link PageStore}. Built
 * on-demand each search (the corpus is small enough to make rebuild cheap,
 * and it keeps the index always fresh without a notification protocol).
 *
 * <p>The scoring is the standard BM25 Okapi formula with k1 = 1.2 and b = 0.75.
 * Tokenisation is deliberately simple — lowercase, split on non-word
 * characters, drop length &lt; 2 tokens. Complements cosine search by rewarding
 * exact tokens like {@code BRAVE_API_KEY} or {@code gpt-5.2}.
 */
public class KeywordIndex {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int MIN_TOKEN_LENGTH = 2;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9_.-]+");

    private final PageStore pageStore;

    public KeywordIndex(PageStore pageStore) {
        this.pageStore = pageStore;
    }

    public List<SearchResult> search(String query, int maxResults) {
        var queryTokens = tokenise(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        var pages = allPages();
        if (pages.isEmpty()) {
            return List.of();
        }

        var tokenised = new ArrayList<TokenisedPage>(pages.size());
        long totalLength = 0;
        for (var page : pages) {
            var tokens = tokenise(page.content());
            tokenised.add(new TokenisedPage(page, countTokens(tokens), tokens.size()));
            totalLength += tokens.size();
        }
        double avgLength = totalLength / (double) tokenised.size();

        var docFreq = documentFrequencies(tokenised, queryTokens);
        int n = tokenised.size();

        var scored = new ArrayList<SearchResult>(tokenised.size());
        for (var doc : tokenised) {
            double score = bm25Score(queryTokens, doc, avgLength, docFreq, n);
            if (score > 0.0) {
                scored.add(new SearchResult(doc.page, score));
            }
        }

        scored.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return scored.size() > maxResults ? scored.subList(0, maxResults) : scored;
    }

    private List<Page> allPages() {
        return pageStore.recent(Integer.MAX_VALUE);
    }

    private double bm25Score(List<String> queryTokens, TokenisedPage doc,
                             double avgLength, Map<String, Integer> docFreq, int n) {
        double score = 0.0;
        for (var term : queryTokens) {
            int f = doc.termCounts.getOrDefault(term, 0);
            if (f == 0) continue;
            int df = docFreq.getOrDefault(term, 0);
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            double norm = f * (K1 + 1) / (f + K1 * (1 - B + B * doc.length / avgLength));
            score += idf * norm;
        }
        return score;
    }

    private Map<String, Integer> documentFrequencies(List<TokenisedPage> docs, List<String> queryTokens) {
        var df = new HashMap<String, Integer>();
        for (var term : queryTokens) {
            int count = 0;
            for (var doc : docs) {
                if (doc.termCounts.containsKey(term)) count++;
            }
            df.put(term, count);
        }
        return df;
    }

    private Map<String, Integer> countTokens(List<String> tokens) {
        var counts = new HashMap<String, Integer>();
        for (var token : tokens) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    static List<String> tokenise(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var tokens = new ArrayList<String>();
        for (var raw : TOKEN_SPLIT.split(text.toLowerCase())) {
            if (raw.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private record TokenisedPage(Page page, Map<String, Integer> termCounts, int length) {
    }
}
