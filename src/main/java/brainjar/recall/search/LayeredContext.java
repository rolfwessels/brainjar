package brainjar.recall.search;

import brainjar.recall.UserShelves;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;
import brainjar.recall.store.SummaryStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LayeredContext {

    static final int L1_MAX_PAGES = 15;
    static final int L1_MAX_CHARS = 3200;
    static final int L2_MAX_CHARS = 2000;
    static final int L2_MAX_ITEMS = 10;
    static final int SNIPPET_LENGTH = 200;

    static final int BRIEFING_SHELVES_MAX = 8;
    static final int BRIEFING_RECENT_ITEMS = 6;
    static final int BRIEFING_RECENT_MAX_CHARS = 400;
    static final int BRIEFING_SNIPPET_LENGTH = 100;

    static final int SCOPED_SEARCH_CANDIDATE_MULTIPLIER = 3;

    private final String identity;
    private final PageStore pageStore;
    private final HybridSearcher hybridSearcher;
    private final SummaryStore summaryStore;

    public LayeredContext(String identity, PageStore pageStore) {
        this(identity, pageStore, null, null);
    }

    public LayeredContext(String identity, PageStore pageStore, HybridSearcher hybridSearcher) {
        this(identity, pageStore, hybridSearcher, null);
    }

    public LayeredContext(String identity,
                          PageStore pageStore,
                          HybridSearcher hybridSearcher,
                          SummaryStore summaryStore) {
        this.identity = identity != null ? identity : "";
        this.pageStore = pageStore;
        this.hybridSearcher = hybridSearcher;
        this.summaryStore = summaryStore;
    }

    public String wakeUp() {
        var sb = new StringBuilder();
        appendL0(sb);
        appendL1(sb);
        appendL2(sb);
        return sb.toString().strip();
    }

    /**
     * Operator-facing briefing. Shows raw storage shelf names (including
     * {@code user:<UID>:} prefixes) across every page in the store, regardless
     * of owner. Use this for debugging from the CLI; for the per-turn briefing
     * Perry sees, use {@link #briefing(String)}.
     *
     * <p>Returns an empty string if the store is empty.
     */
    public String briefing() {
        return buildBriefing(null);
    }

    /**
     * Per-turn "what's in memory right now" brief for injection into Perry's
     * system prompt. Filtered to {@code userId}'s own shelves plus global
     * shelves; storage prefixes are stripped on the way out so Perry only
     * ever sees short display names like {@code wines}, {@code notes},
     * {@code docs}.
     *
     * <p>Returns an empty string if the user has no visible memory.
     */
    public String briefing(String userId) {
        return buildBriefing(userId);
    }

    private String buildBriefing(String userId) {
        var allPages = pageStore.recent(Integer.MAX_VALUE);
        var visiblePages = allPages.stream()
                .filter(p -> userId == null || UserShelves.isVisibleTo(userId, p.book().shelf().name()))
                .toList();
        if (visiblePages.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        sb.append("## Memory briefing\n");
        appendShelfInventory(sb, visiblePages, userId);
        appendRecentHighlights(sb, userId);
        return sb.toString().strip();
    }

    private void appendShelfInventory(StringBuilder sb,
                                      List<brainjar.recall.model.Page> visiblePages,
                                      String userId) {
        var counts = visiblePages.stream().collect(Collectors.groupingBy(
                p -> displayShelf(userId, p.book().shelf().name()),
                LinkedHashMap::new,
                Collectors.summingInt(p -> 1)));

        var sortedEntries = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();
        var top = sortedEntries.stream()
                .limit(BRIEFING_SHELVES_MAX)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));

        sb.append("Shelves: ").append(top);
        if (sortedEntries.size() > BRIEFING_SHELVES_MAX) {
            sb.append(", +").append(sortedEntries.size() - BRIEFING_SHELVES_MAX).append(" more");
        }
        sb.append("\n");
    }

    private void appendRecentHighlights(StringBuilder sb, String userId) {
        // Pull a wider net so the user filter doesn't starve the highlight list.
        int candidateLimit = userId == null ? BRIEFING_RECENT_ITEMS : BRIEFING_RECENT_ITEMS * 4;
        var candidates = pageStore.recent(candidateLimit);
        if (candidates.isEmpty()) {
            return;
        }

        var highlights = new StringBuilder();
        int chars = 0;
        int picked = 0;
        for (var page : candidates) {
            if (userId != null && !UserShelves.isVisibleTo(userId, page.book().shelf().name())) {
                continue;
            }
            var gist = highlightFor(page);
            if (gist.isBlank()) continue;
            var line = "- [%s] %s".formatted(displayShelf(userId, page.book().shelf().name()), gist);
            if (chars + line.length() > BRIEFING_RECENT_MAX_CHARS) break;
            highlights.append(line).append("\n");
            chars += line.length() + 1;
            if (++picked >= BRIEFING_RECENT_ITEMS) break;
        }

        if (highlights.length() > 0) {
            sb.append("Recent:\n").append(highlights);
        }
    }

    private String highlightFor(brainjar.recall.model.Page page) {
        if (summaryStore != null) {
            var maybe = summaryStore.get(page.id());
            if (maybe.isPresent()) {
                var summary = maybe.get();
                if (!summary.keySentence().isBlank()) {
                    return truncate(summary.keySentence(), BRIEFING_SNIPPET_LENGTH);
                }
                if (!summary.topics().isEmpty()) {
                    return String.join(", ", summary.topics());
                }
            }
        }
        return truncate(page.content(), BRIEFING_SNIPPET_LENGTH);
    }

    /**
     * Browse the most recent pages on a shelf. {@code shelfName} must be the
     * full storage name (e.g. {@code "user:42:wines"} or {@code "docs"});
     * the caller is responsible for any user→storage translation.
     */
    public String recall(String shelfName) {
        return recall(shelfName, null, null);
    }

    /**
     * Browse a shelf, optionally narrowing by query. {@code shelfName} must
     * be the full storage name. With a blank/null {@code query} this returns
     * the most recent pages on the shelf; otherwise it runs a vector search
     * scoped to the shelf. Output uses raw storage shelf names — see
     * {@link #recall(String, String, String)} to strip the user prefix on
     * display.
     */
    public String recall(String shelfName, String query) {
        return recall(shelfName, query, null);
    }

    /**
     * Browse a shelf, optionally narrowing by query, with shelf names
     * displayed using {@code userId}'s perspective (own shelves have the
     * {@code user:<UID>:} prefix stripped; global shelves pass through).
     */
    public String recall(String shelfName, String query, String userId) {
        if (shelfName == null || shelfName.isBlank()) {
            return "";
        }
        if (query == null || query.isBlank()) {
            var pages = pageStore.recentByShelf(shelfName, L1_MAX_PAGES);
            var results = pages.stream()
                    .map(p -> new SearchResult(p, 1.0))
                    .toList();
            return formatResults(results, L2_MAX_CHARS, userId);
        }
        var results = pageStore.search(query, L1_MAX_PAGES, shelfName);
        return formatResults(results, L2_MAX_CHARS, userId);
    }

    /**
     * Operator-facing search across every page in the store, ranked by the
     * hybrid (cosine + BM25) searcher when available, else cosine alone.
     * No user filtering. For the per-turn search Perry sees, use
     * {@link #search(String, int, String)}.
     */
    public String search(String query, int maxResults) {
        var results = hybridSearcher != null
                ? hybridSearcher.search(query, maxResults)
                : pageStore.search(query, maxResults);
        return formatResults(results, Integer.MAX_VALUE, null);
    }

    /**
     * Per-turn search filtered to {@code userId}'s shelves plus global
     * shelves. Pulls a wider candidate set from the underlying searcher so
     * the post-filter doesn't starve the result list, then trims to
     * {@code maxResults}. Storage shelf prefixes are stripped on the way out.
     */
    public String search(String query, int maxResults, String userId) {
        if (maxResults <= 0) {
            return "";
        }
        int candidates = maxResults * SCOPED_SEARCH_CANDIDATE_MULTIPLIER;
        var raw = hybridSearcher != null
                ? hybridSearcher.search(query, candidates)
                : pageStore.search(query, candidates);
        var filtered = raw.stream()
                .filter(r -> UserShelves.isVisibleTo(userId, r.page().book().shelf().name()))
                .limit(maxResults)
                .toList();
        return formatResults(filtered, Integer.MAX_VALUE, userId);
    }

    private void appendL0(StringBuilder sb) {
        if (!identity.isBlank()) {
            sb.append("## Identity\n").append(identity).append("\n\n");
        }
    }

    private void appendL2(StringBuilder sb) {
        if (summaryStore == null || summaryStore.size() == 0) {
            return;
        }

        var recent = pageStore.recent(L2_MAX_ITEMS);
        if (recent.isEmpty()) {
            return;
        }

        var items = new StringBuilder();
        int count = 0;
        int chars = 0;
        for (var page : recent) {
            var maybe = summaryStore.get(page.id());
            if (maybe.isEmpty()) continue;
            var summary = maybe.get();
            if (summary.isEmpty()) continue;

            var line = formatSummaryLine(page.book().shelf().name(), summary);
            if (chars + line.length() > L2_MAX_CHARS) break;
            items.append(line).append("\n");
            chars += line.length();
            count++;
            if (count >= L2_MAX_ITEMS) break;
        }

        if (count > 0) {
            sb.append("\n## Shelf Summaries\n").append(items);
        }
    }

    private static String formatSummaryLine(String shelf, brainjar.recall.model.Summary summary) {
        var parts = new StringBuilder("- [").append(shelf).append("] ");
        if (!summary.keySentence().isBlank()) {
            parts.append(summary.keySentence());
        } else if (!summary.topics().isEmpty()) {
            parts.append(String.join(", ", summary.topics()));
        } else {
            parts.append(String.join(", ", summary.entities()));
        }
        if (!summary.flags().isEmpty()) {
            parts.append(" [").append(String.join(",", summary.flags())).append("]");
        }
        return parts.toString();
    }

    private void appendL1(StringBuilder sb) {
        var pages = pageStore.recent(L1_MAX_PAGES);
        if (pages.isEmpty()) {
            return;
        }

        sb.append("## Key Memories\n");
        int totalChars = 0;
        for (var page : pages) {
            var snippet = truncate(page.content(), SNIPPET_LENGTH);
            if (totalChars + snippet.length() > L1_MAX_CHARS) {
                break;
            }
            sb.append("- [%s/%s] %s\n".formatted(
                    page.book().shelf().name(),
                    page.book().title(),
                    snippet
            ));
            totalChars += snippet.length();
        }
    }

    private String formatResults(List<SearchResult> results, int maxChars, String userId) {
        if (results.isEmpty()) {
            return "";
        }
        int totalChars = 0;
        var sb = new StringBuilder();
        for (var result : results) {
            var shelfName = displayShelf(userId, result.page().book().shelf().name());
            var line = "[%s/%s] (%.2f) %s".formatted(
                    shelfName,
                    result.page().book().title(),
                    result.score(),
                    result.page().content()
            );
            if (totalChars + line.length() > maxChars) {
                sb.append(truncate(line, maxChars - totalChars));
                break;
            }
            sb.append(line).append("\n\n");
            totalChars += line.length();
        }
        return sb.toString().strip();
    }

    private static String displayShelf(String userId, String storageShelf) {
        if (userId == null) {
            return storageShelf;
        }
        return UserShelves.toDisplay(userId, storageShelf);
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
