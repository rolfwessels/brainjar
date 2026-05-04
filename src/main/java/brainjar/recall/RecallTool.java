package brainjar.recall;

import brainjar.context.UserContext;
import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.Triple;
import brainjar.recall.kg.extract.async.ExtractionQueue;
import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.search.LayeredContext;
import brainjar.recall.search.Searcher;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RecallTool {

    private static final Logger log = LoggerFactory.getLogger(RecallTool.class);
    private static final String CAPTURES_ROOT = "captures";
    private static final int FORGET_SCAN_SIZE = 20;
    private static final int FORGET_CANDIDATE_COUNT = 5;
    private static final int SEARCH_INTERNAL_LIMIT = 15;
    private static final int MOVE_BATCH_LIMIT = 1000;

    private final Searcher searcher;
    private final LayeredContext layeredContext;
    private final PageStore pageStore;
    private final KnowledgeGraph knowledgeGraph;
    private final ExtractionQueue extractionQueue;

    public RecallTool(Searcher searcher,
                      LayeredContext layeredContext,
                      PageStore pageStore,
                      KnowledgeGraph knowledgeGraph,
                      ExtractionQueue extractionQueue) {
        this.searcher = searcher;
        this.layeredContext = layeredContext;
        this.pageStore = pageStore;
        this.knowledgeGraph = knowledgeGraph;
        this.extractionQueue = extractionQueue;
    }

    @Tool("Search long-term memory for information about a topic. Use when asked about past conversations, decisions, or stored knowledge. Scoped to the current user's memories plus globally mined knowledge.")
    public String searchMemory(String query) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        var vectorResults = layeredContext.search(query, SEARCH_INTERNAL_LIMIT, userId);
        var facts = searcher.findRelatedFacts(query, LocalDate.now()).stream()
                .map(this::formatTriple)
                .collect(Collectors.joining("\n"));

        var elapsedMs = System.currentTimeMillis() - start;
        var pageHits = vectorResults.isBlank() ? 0 : countLines(vectorResults);
        var factHits = facts.isBlank() ? 0 : countLines(facts);
        log.info("searchMemory user={} query=\"{}\" pages={} facts={} took={}ms",
                userId, query, pageHits, factHits, elapsedMs);

        if (vectorResults.isBlank() && facts.isBlank()) {
            return "No memories found for: " + query;
        }

        var sb = new StringBuilder();
        if (!vectorResults.isBlank()) {
            sb.append("## Relevant Memories\n").append(vectorResults);
        }
        if (!facts.isBlank()) {
            sb.append("\n\n## Known Facts\n").append(facts);
        }
        return sb.toString();
    }

    @Tool("List every shelf you can recall from, with page counts. Shows global shelves (shared mined docs) and the current user's private shelves. Useful when you're not sure which shelf a topic lives on before calling recall().")
    public String listShelves() {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        var pages = pageStore.recent(Integer.MAX_VALUE);
        var counts = pages.stream().collect(Collectors.groupingBy(
                p -> p.book().shelf().name(),
                LinkedHashMap::new,
                Collectors.summingInt(p -> 1)));

        var global = new LinkedHashMap<String, Integer>();
        var mine = new LinkedHashMap<String, Integer>();
        for (var entry : counts.entrySet()) {
            var name = entry.getKey();
            if (UserShelves.isOwnedBy(userId, name)) {
                mine.put(UserShelves.toDisplay(userId, name), entry.getValue());
            } else if (!UserShelves.isUserScoped(name)) {
                global.put(name, entry.getValue());
            }
        }

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("listShelves user={} global={} mine={} took={}ms",
                userId, global.size(), mine.size(), elapsedMs);

        if (global.isEmpty() && mine.isEmpty()) {
            return "No shelves available yet — memory is empty for this user.";
        }

        var sb = new StringBuilder();
        if (!global.isEmpty()) {
            sb.append("Global shelves:\n");
            appendShelfLines(sb, global);
        }
        if (!mine.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("Your shelves:\n");
            appendShelfLines(sb, mine);
        }
        return sb.toString().strip();
    }

    @Tool("Browse memories on a specific shelf. Pass the short shelf label you see in listShelves() (e.g. 'wines', 'notes', 'docs') — never the storage prefix. Without a query you get the most recent pages on the shelf; with a query you get a search scoped to that shelf.")
    public String recall(String shelfName, String query) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (shelfName == null || shelfName.isBlank()) {
            return "No shelf specified.";
        }

        var resolved = resolveReadableShelf(userId, shelfName);
        if (resolved == null) {
            log.info("recall user={} shelf=\"{}\" result=unknown-shelf", userId, shelfName);
            return "No memories on shelf: " + shelfName;
        }

        var result = layeredContext.recall(resolved, query, userId);

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("recall user={} shelf=\"{}\" resolved=\"{}\" query=\"{}\" resultChars={} took={}ms",
                userId, shelfName, resolved, query == null ? "" : query, result.length(), elapsedMs);

        return result.isBlank() ? "No memories on shelf: " + shelfName : result;
    }

    /**
     * Resolve a Perry-facing shelf label to a real storage shelf name. Tries
     * the user's own shelf first, then a literal global shelf with the same
     * name (so {@code recall("docs")} hits the mined corpus). Returns
     * {@code null} when neither has any pages.
     */
    private String resolveReadableShelf(String userId, String displayShelf) {
        var ownStorage = UserShelves.toStorage(userId, displayShelf);
        if (!pageStore.recentByShelf(ownStorage, 1).isEmpty()) {
            return ownStorage;
        }
        var literal = displayShelf.strip();
        if (!UserShelves.isUserScoped(literal) && !pageStore.recentByShelf(literal, 1).isEmpty()) {
            return literal;
        }
        return null;
    }

    @Tool("Store a single new memory about the user. Use for one-off durable facts — a preference, a project, a decision. "
            + "Pick a shelf label that describes the topic (examples: 'profile', 'preferences', 'projects', 'tech', 'notes', 'watchlist', 'books'). "
            + "Reuse an existing shelf when one fits — check the memory briefing or call listShelves() if unsure. "
            + "For lists of items, use rememberMany instead.")
    public String remember(String content, String shelf) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (content == null || content.isBlank()) {
            return "Nothing to remember — content was empty.";
        }
        var shelfLabel = UserShelves.normalise(shelf);
        var today = LocalDate.now();
        var book = userDailyCaptureBook(userId, shelfLabel, today);

        var chunkIndex = pageStore.nextChunkIndex(book);
        var pageId = Page.generateId(book.sourcePath().toString(), chunkIndex);
        var page = new Page(pageId, content.strip(), chunkIndex, book);
        pageStore.store(List.of(page));
        extractionQueue.enqueue(pageId);

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("remember user={} shelf=\"{}\" pageId={} chars={} took={}ms",
                userId, book.shelf().name(), pageId, content.length(), elapsedMs);

        return "Remembered on shelf \"" + shelfLabel + "\" (id " + pageId + ").";
    }

    @Tool("Store multiple memories in one call. Each item picks its own shelf, so a mixed list (e.g. a few movies and a few TV series) can land across multiple shelves in a single call. "
            + "Use this whenever the user gives you a list of things to remember — do not call remember in a loop. "
            + "Reuse existing shelf names when they fit (check the memory briefing or listShelves before inventing new ones). "
            + "Pages with blank content are skipped silently.")
    public String rememberMany(List<MemoryItem> items) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (items == null || items.isEmpty()) {
            return "Nothing to remember — no items supplied.";
        }

        var today = LocalDate.now();
        var bookByShelf = new LinkedHashMap<String, Book>();
        var nextChunkByShelf = new LinkedHashMap<String, Integer>();
        var pages = new ArrayList<Page>();
        var countByShelf = new LinkedHashMap<String, Integer>();
        int skipped = 0;

        for (var item : items) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                skipped++;
                continue;
            }
            var shelfLabel = UserShelves.normalise(item.shelf());
            var book = bookByShelf.computeIfAbsent(shelfLabel,
                    label -> userDailyCaptureBook(userId, label, today));
            var chunkIndex = nextChunkByShelf.computeIfAbsent(shelfLabel,
                    label -> pageStore.nextChunkIndex(book));
            var pageId = Page.generateId(book.sourcePath().toString(), chunkIndex);
            pages.add(new Page(pageId, item.content().strip(), chunkIndex, book));
            nextChunkByShelf.put(shelfLabel, chunkIndex + 1);
            countByShelf.merge(shelfLabel, 1, Integer::sum);
        }

        if (pages.isEmpty()) {
            return "Nothing to remember — all items were empty.";
        }
        pageStore.store(pages);
        for (var page : pages) {
            extractionQueue.enqueue(page.id());
        }

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("rememberMany user={} shelves={} stored={} skipped={} took={}ms",
                userId, countByShelf, pages.size(), skipped, elapsedMs);

        var summary = countByShelf.entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.joining(", "));
        var response = new StringBuilder("Stored ").append(pages.size())
                .append(pages.size() == 1 ? " memory" : " memories")
                .append(" across shelves: ").append(summary).append(".");
        if (skipped > 0) {
            response.append(" Skipped ").append(skipped).append(" blank item(s).");
        }
        return response.toString();
    }

    @Tool("Move every page on one of the current user's shelves to another shelf, atomically. Use this for 'move my X from shelf A to shelf B' rather than the find/forget/remember dance — that loses items silently. Refuses to move from global shelves like 'docs'.")
    public String moveToShelf(String fromShelf, String toShelf) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (fromShelf == null || fromShelf.isBlank() || toShelf == null || toShelf.isBlank()) {
            return "Move refused — both fromShelf and toShelf are required.";
        }

        var fromLabel = UserShelves.normalise(fromShelf);
        var toLabel = UserShelves.normalise(toShelf);
        if (fromLabel.equals(toLabel)) {
            return "Move refused — fromShelf and toShelf are the same (\"" + fromLabel + "\").";
        }

        var fromStorage = UserShelves.toStorage(userId, fromLabel);
        var sourcePages = pageStore.recentByShelf(fromStorage, MOVE_BATCH_LIMIT);
        if (sourcePages.isEmpty()) {
            log.info("moveToShelf user={} from=\"{}\" to=\"{}\" result=empty-source",
                    userId, fromLabel, toLabel);
            return "Nothing to move — shelf \"" + fromLabel + "\" has no pages.";
        }

        var today = LocalDate.now();
        var destBook = userDailyCaptureBook(userId, toLabel, today);
        var nextChunk = pageStore.nextChunkIndex(destBook);
        var newPages = new ArrayList<Page>(sourcePages.size());
        var sourceIds = new ArrayList<String>(sourcePages.size());

        for (var page : sourcePages) {
            var newId = Page.generateId(destBook.sourcePath().toString(), nextChunk);
            newPages.add(new Page(newId, page.content(), nextChunk, destBook));
            sourceIds.add(page.id());
            nextChunk++;
        }

        pageStore.store(newPages);
        for (var id : sourceIds) {
            pageStore.deletePage(id);
            knowledgeGraph.invalidateByPageId(id, today);
            extractionQueue.cancel(id);
        }
        for (var page : newPages) {
            extractionQueue.enqueue(page.id());
        }

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("moveToShelf user={} from=\"{}\" to=\"{}\" moved={} took={}ms",
                userId, fromLabel, toLabel, sourcePages.size(), elapsedMs);

        return "Moved " + sourcePages.size()
                + (sourcePages.size() == 1 ? " page" : " pages")
                + " from \"" + fromLabel + "\" to \"" + toLabel + "\".";
    }

    @Tool("Find candidate memories to forget. Returns up to 5 of the current user's memory pages matching the phrase, each with a pageId, shelf and preview. DELETES NOTHING. Always call this before forgetById so you — and if ambiguous, the user — can confirm the right target. If several candidates plausibly match, ask the user which pageId to forget.")
    public String findForgetCandidates(String phrase) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (phrase == null || phrase.isBlank()) {
            return "No candidates — no phrase supplied.";
        }
        var candidates = userScopedCandidates(userId, phrase, FORGET_CANDIDATE_COUNT);

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("findForgetCandidates user={} phrase=\"{}\" hits={} took={}ms",
                userId, phrase, candidates.size(), elapsedMs);

        if (candidates.isEmpty()) {
            return "No candidates — no matching user memory for: " + phrase;
        }

        var sb = new StringBuilder("Candidates for \"").append(phrase).append("\":\n");
        int i = 1;
        for (var result : candidates) {
            var page = result.page();
            var displayShelfName = UserShelves.toDisplay(userId, page.book().shelf().name());
            sb.append(i++).append(". pageId=").append(page.id())
                    .append(" shelf=").append(displayShelfName)
                    .append(" score=").append(String.format("%.2f", result.score()))
                    .append("\n   ").append(truncate(page.content(), 140))
                    .append("\n");
        }
        sb.append("Pick one and call forgetById(pageId). If more than one plausibly matches, ask the user which pageId to forget.");
        return sb.toString();
    }

    @Tool("Look up currently-true facts about a subject in the knowledge graph. Returns structured triples "
            + "(subject predicate object) filtered to what is valid as of today. Use when the user asks "
            + "\"what's true about X\" or you need a clean current-state answer rather than a text search. "
            + "Returns an empty message if the subject is unknown.")
    public String graphFacts(String subject) {
        if (subject == null || subject.isBlank()) {
            return "No subject specified.";
        }
        var triples = searcher.findRelatedFacts(subject, LocalDate.now());
        return formatTripleList("Facts about \"" + subject + "\":", triples);
    }

    @Tool("List every triple (open and closed) touching an entity, optionally filtered to a single predicate. "
            + "Use when the user asks \"how has X changed\" or \"what did X used to be\". Ordered chronologically "
            + "by valid_from.")
    public String graphHistory(String entity, String predicate) {
        if (entity == null || entity.isBlank()) {
            return "No entity specified.";
        }
        var triples = knowledgeGraph.history(entity, predicate);
        var header = (predicate == null || predicate.isBlank())
                ? "History for \"" + entity + "\":"
                : "History for \"" + entity + "\" predicate=\"" + predicate + "\":";
        return formatTripleList(header, triples);
    }

    @Tool("List currently-true facts involving an entity as either subject or object. Use when the user asks "
            + "\"who/what is related to X\" or you want to reason about immediate neighbours rather than just "
            + "the outgoing facts from X.")
    public String graphNeighbors(String entity) {
        if (entity == null || entity.isBlank()) {
            return "No entity specified.";
        }
        var triples = knowledgeGraph.neighbors(entity);
        return formatTripleList("Neighbours of \"" + entity + "\":", triples);
    }

    @Tool("Explain a single fact: return the triple plus the source page that asserted it. "
            + "Call this after graphFacts/graphNeighbors when the user asks \"why do you think that?\" or "
            + "\"where did that come from?\". Pass the triple id (starts with \"t_\").")
    public String graphExplain(String tripleId) {
        if (tripleId == null || tripleId.isBlank()) {
            return "No triple id specified.";
        }
        var triple = knowledgeGraph.findTriple(tripleId);
        if (triple.isEmpty()) {
            return "No triple found with id: " + tripleId;
        }
        var t = triple.get();
        var sb = new StringBuilder();
        sb.append("Fact: ").append(formatTriple(t).substring(2)).append("\n");
        sb.append("Confidence: ").append(String.format("%.2f", t.confidence())).append("\n");
        if (t.validFrom() != null) {
            sb.append("Valid from: ").append(t.validFrom());
            if (t.validTo() != null) {
                sb.append(" to ").append(t.validTo());
            }
            sb.append("\n");
        }
        if (t.sourcePageId() == null || t.sourcePageId().isBlank()) {
            sb.append("Source: (unknown — fact was asserted without a source page)");
            return sb.toString();
        }
        var page = pageStore.findById(t.sourcePageId());
        if (page.isEmpty()) {
            sb.append("Source: page ").append(t.sourcePageId())
                    .append(" (no longer in the store — the memory was forgotten)");
            return sb.toString();
        }
        var p = page.get();
        sb.append("Source page: ").append(p.id()).append("\n");
        sb.append("Shelf: ").append(p.book().shelf().name()).append("\n");
        sb.append("Excerpt:\n").append(truncate(p.content(), 400));
        return sb.toString();
    }

    @Tool("Forget a specific memory by its pageId. Only deletes pages belonging to the current user. Always obtain the pageId via findForgetCandidates first — never guess it.")
    public String forgetById(String pageId) {
        var userId = UserContext.getOrAnonymous();
        var start = System.currentTimeMillis();

        if (pageId == null || pageId.isBlank()) {
            return "Nothing to forget — no pageId supplied.";
        }

        var found = pageStore.findById(pageId);
        if (found.isEmpty()) {
            log.info("forgetById user={} pageId={} result=not-found", userId, pageId);
            return "Nothing to forget — no page with id: " + pageId;
        }

        var page = found.get();
        var shelfName = page.book().shelf().name();
        if (!UserShelves.isOwnedBy(userId, shelfName)) {
            log.warn("forgetById user={} pageId={} shelf={} result=refused-not-owner",
                    userId, pageId, shelfName);
            return "Refused — that page is not one of your memories.";
        }

        pageStore.deletePage(pageId);
        extractionQueue.cancel(pageId);
        int closedTriples = knowledgeGraph.invalidateByPageId(pageId, LocalDate.now());

        var elapsedMs = System.currentTimeMillis() - start;
        log.info("forgetById user={} pageId={} shelf={} closedTriples={} took={}ms",
                userId, pageId, shelfName, closedTriples, elapsedMs);

        var preview = truncate(page.content(), 80);
        var displayShelfName = UserShelves.toDisplay(userId, shelfName);
        return "Forgot: \"" + preview + "\" (shelf " + displayShelfName + ")"
                + (closedTriples > 0 ? " — also closed " + closedTriples + " related fact(s)." : ".");
    }

    private List<SearchResult> userScopedCandidates(String userId, String phrase, int limit) {
        return pageStore.search(phrase, FORGET_SCAN_SIZE).stream()
                .filter(r -> UserShelves.isOwnedBy(userId, r.page().book().shelf().name()))
                .limit(limit)
                .toList();
    }

    private static Book userDailyCaptureBook(String userId, String shelfLabel, LocalDate date) {
        var shelf = new Shelf(UserShelves.toStorage(userId, shelfLabel));
        var sourcePath = Path.of(CAPTURES_ROOT, userId, shelfLabel, date.toString() + ".md");
        var title = shelfLabel + " captures " + date;
        return new Book(sourcePath, title, shelf, Instant.now());
    }

    private String formatTriple(Triple triple) {
        return "- %s %s %s".formatted(triple.subject(), triple.predicate(), triple.object());
    }

    private String formatTripleList(String header, List<Triple> triples) {
        if (triples.isEmpty()) {
            return header + "\n(nothing found)";
        }
        var sb = new StringBuilder(header).append("\n");
        for (var t : triples) {
            sb.append(formatTriple(t));
            if (t.validTo() != null) {
                sb.append("  [closed ").append(t.validTo()).append("]");
            } else if (t.validFrom() != null) {
                sb.append("  [since ").append(t.validFrom()).append("]");
            }
            if (t.id() != null) {
                sb.append("  id=").append(t.id());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static void appendShelfLines(StringBuilder sb, Map<String, Integer> shelves) {
        shelves.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sb.append("- ").append(e.getKey())
                        .append(" (").append(e.getValue()).append(")\n"));
    }

    private static int countLines(String text) {
        if (text.isBlank()) return 0;
        return (int) text.lines().filter(l -> !l.isBlank()).count();
    }

    private static String truncate(String text, int max) {
        var single = text.replaceAll("\\s+", " ").strip();
        if (single.length() <= max) return single;
        return single.substring(0, max - 3) + "...";
    }
}
