package brainjar.recall.search;

import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Hybrid retriever that combines cosine similarity (via {@link PageStore})
 * with BM25 keyword scoring ({@link KeywordIndex}) using
 * <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">
 * Reciprocal Rank Fusion (RRF)</a>.
 *
 * <p>RRF is intentionally score-agnostic: it only looks at the *rank* of a
 * document in each ranked list, so we don't need to normalise cosine vs
 * BM25 scores. {@code score(doc) = Σ 1 / (k + rank_i(doc))} with
 * {@code k = 60} per the canonical paper.
 */
public class HybridSearcher {

    private static final int RRF_K = 60;
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final PageStore pageStore;
    private final KeywordIndex keywordIndex;

    public HybridSearcher(PageStore pageStore, KeywordIndex keywordIndex) {
        this.pageStore = pageStore;
        this.keywordIndex = keywordIndex;
    }

    public List<SearchResult> search(String query, int maxResults) {
        int candidateCount = maxResults * CANDIDATE_MULTIPLIER;
        var cosine = pageStore.search(query, candidateCount);
        var keyword = keywordIndex.search(query, candidateCount);
        return fuse(cosine, keyword, maxResults);
    }

    public List<SearchResult> search(String query, int maxResults, String shelfName) {
        int candidateCount = maxResults * CANDIDATE_MULTIPLIER;
        var cosine = pageStore.search(query, candidateCount, shelfName);
        var keyword = keywordIndex.search(query, candidateCount).stream()
                .filter(r -> r.page().book().shelf().name().equals(shelfName))
                .toList();
        return fuse(cosine, keyword, maxResults);
    }

    static List<SearchResult> fuse(List<SearchResult> cosine,
                                   List<SearchResult> keyword,
                                   int maxResults) {
        var fused = new HashMap<String, Double>();
        var pages = new HashMap<String, SearchResult>();

        accumulate(cosine, fused, pages);
        accumulate(keyword, fused, pages);

        var merged = new ArrayList<SearchResult>();
        for (var entry : fused.entrySet()) {
            var best = pages.get(entry.getKey());
            merged.add(new SearchResult(best.page(), entry.getValue()));
        }
        merged.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return merged.size() > maxResults ? merged.subList(0, maxResults) : merged;
    }

    private static void accumulate(List<SearchResult> ranked,
                                   HashMap<String, Double> fused,
                                   HashMap<String, SearchResult> pages) {
        for (int i = 0; i < ranked.size(); i++) {
            var result = ranked.get(i);
            var pageId = result.page().id();
            double contribution = 1.0 / (RRF_K + i + 1);
            fused.merge(pageId, contribution, Double::sum);
            pages.putIfAbsent(pageId, result);
        }
    }
}
