package brainjar.recall;

import brainjar.recall.export.BookExporter;
import brainjar.recall.ingest.Miner;
import brainjar.recall.kg.Entity;
import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.Triple;
import brainjar.recall.kg.extract.async.ExtractionQueue;
import brainjar.recall.model.Shelf;
import brainjar.recall.search.HybridSearcher;
import brainjar.recall.search.LayeredContext;
import brainjar.recall.store.PageStore;
import brainjar.schedule.JobStore;
import brainjar.schedule.ScheduleProperties;
import brainjar.schedule.ScheduledJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class RecallCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecallCommand.class);
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DEFAULT_LATEST_LIMIT = 10;
    private static final int SEARCH_SNIPPET_CHARS = 800;
    private static final int LATEST_SNIPPET_CHARS = 200;
    private static final int JOB_PROMPT_PREVIEW = 100;

    private final Miner miner;
    private final PageStore pageStore;
    private final HybridSearcher hybridSearcher;
    private final LayeredContext layeredContext;
    private final KnowledgeGraph knowledgeGraph;
    private final ExtractionQueue extractionQueue;
    private final JobStore jobStore;
    private final ScheduleProperties scheduleProperties;
    private final BookExporter bookExporter;
    private final ApplicationContext context;
    private final Environment environment;

    public RecallCommand(Miner miner, PageStore pageStore, HybridSearcher hybridSearcher,
                         LayeredContext layeredContext,
                         KnowledgeGraph knowledgeGraph,
                         ExtractionQueue extractionQueue,
                         JobStore jobStore,
                         ScheduleProperties scheduleProperties,
                         BookExporter bookExporter,
                         ApplicationContext context, Environment environment) {
        this.miner = miner;
        this.pageStore = pageStore;
        this.hybridSearcher = hybridSearcher;
        this.layeredContext = layeredContext;
        this.knowledgeGraph = knowledgeGraph;
        this.extractionQueue = extractionQueue;
        this.jobStore = jobStore;
        this.scheduleProperties = scheduleProperties;
        this.bookExporter = bookExporter;
        this.context = context;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        var parsed = parseArgs(args.getSourceArgs());
        if (parsed.command() == Command.NONE) {
            return;
        }

        switch (parsed.command()) {
            case MINE -> executeMine(parsed);
            case SEARCH -> executeSearch(parsed);
            case REMOVE_SHELF -> executeRemoveShelf(parsed);
            case BRIEFING -> executeBriefing();
            case LIST_SHELVES -> executeListShelves();
            case LATEST -> executeLatest(parsed);
            case LIST_JOBS -> executeListJobs();
            case EXPORT_KG -> executeExportKg(parsed);
            case EXPORT_MD -> executeExportMd(parsed);
            case REMINE -> executeRemine();
            default -> { }
        }
    }

    private void executeRemine() {
        int enqueued = miner.remineAll(extractionQueue);
        int orphansClosed = knowledgeGraph.sweepOrphans(
                pageId -> pageStore.findById(pageId).isEmpty());

        if (cliQuiet()) {
            out("Queued " + enqueued + " page(s) for re-extraction.");
            if (orphansClosed > 0) {
                out("Closed " + orphansClosed + " orphan triple(s) whose source pages are gone.");
            }
            out("Waiting for the extraction worker to drain (up to 5 minutes)...");
        } else {
            log.info("Remine: enqueued={} orphansClosed={}", enqueued, orphansClosed);
        }

        try {
            boolean drained = extractionQueue.awaitIdle(5 * 60 * 1000);
            if (cliQuiet()) {
                out(drained
                        ? "Extraction complete."
                        : "Worker still busy — re-running --remine later will finish the rest.");
            } else if (!drained) {
                log.warn("Extraction worker still busy after 5 minutes; will resume next run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        shutdown(0);
    }

    private void executeListShelves() {
        var pages = pageStore.recent(Integer.MAX_VALUE);
        var counts = pages.stream().collect(Collectors.groupingBy(
                p -> p.book().shelf().name(),
                LinkedHashMap::new,
                Collectors.summingInt(p -> 1)));

        if (cliQuiet()) {
            if (counts.isEmpty()) {
                out("(no shelves yet — mine documents with --mine first)");
            } else {
                out("Shelves (" + counts.size() + "):");
                counts.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                        .forEach(e -> out("  " + e.getKey() + " (" + e.getValue() + ")"));
            }
            out("");
            out("Store: " + pageStore.size() + " pages indexed.");
        } else {
            log.info("Found {} shelf/shelves across {} pages", counts.size(), pageStore.size());
            counts.forEach((name, count) -> log.info("  {} ({})", name, count));
        }
        shutdown(0);
    }

    private void executeLatest(ParsedArgs parsed) {
        var limit = parsed.maxResults() > 0 ? parsed.maxResults() : DEFAULT_LATEST_LIMIT;
        var shelfFilter = parsed.shelfName();

        var stream = pageStore.recent(Integer.MAX_VALUE).stream();
        if (shelfFilter != null) {
            stream = stream.filter(p -> p.book().shelf().name().equals(shelfFilter));
        }
        var pages = stream.limit(limit).toList();

        if (cliQuiet()) {
            if (shelfFilter != null) {
                out("Latest " + pages.size() + " page(s) on shelf \"" + shelfFilter + "\":");
            } else {
                out("Latest " + pages.size() + " page(s) across all shelves:");
            }
            out("");
            if (pages.isEmpty()) {
                out(shelfFilter != null
                        ? "(no pages on that shelf)"
                        : "(no pages stored)");
            } else {
                for (int i = 0; i < pages.size(); i++) {
                    var page = pages.get(i);
                    var book = page.book();
                    out("— " + (i + 1) + " — " + book.shelf().name());
                    out("  id:     " + page.id());
                    out("  source: " + book.sourcePath());
                    if (book.lastModified() != null) {
                        out("  when:   " + formatInstant(book.lastModified()));
                    }
                    out("");
                    out(wrapForDisplay(truncate(page.content(), LATEST_SNIPPET_CHARS), 78));
                    out("");
                }
            }
            out("Store: " + pageStore.size() + " pages indexed.");
        } else {
            log.info("Latest {} page(s){}", pages.size(),
                    shelfFilter != null ? " on shelf '" + shelfFilter + "'" : "");
            for (int i = 0; i < pages.size(); i++) {
                var page = pages.get(i);
                log.info("  [{}] shelf={} id={} source={}", i + 1,
                        page.book().shelf().name(), page.id(), page.book().sourcePath());
                log.info("       {}", truncate(page.content(), LATEST_SNIPPET_CHARS));
            }
        }
        shutdown(0);
    }

    private void executeListJobs() {
        var jobs = jobStore.all().stream()
                .sorted(Comparator.comparing(ScheduledJob::createdAt))
                .toList();

        if (cliQuiet()) {
            if (jobs.isEmpty()) {
                out("(no scheduled jobs)");
            } else {
                out("Scheduled jobs (" + jobs.size() + "):");
                out("");
                for (var job : jobs) {
                    out("— " + job.id() + "  user=" + job.userId());
                    switch (job.kind()) {
                        case ONCE -> out("  ONCE at " + formatInstant(job.fireAt()));
                        case CRON -> out("  CRON \"" + job.cron() + "\"");
                    }
                    if (job.note() != null && !job.note().isBlank()) {
                        out("  note:   " + job.note());
                    }
                    out("  prompt: " + truncate(job.prompt(), JOB_PROMPT_PREVIEW));
                    out("");
                }
            }
        } else {
            log.info("Found {} scheduled job(s)", jobs.size());
            for (var job : jobs) {
                log.info("  id={} user={} kind={} note={} prompt={}",
                        job.id(), job.userId(), job.kind(),
                        job.note() == null ? "(none)" : job.note(),
                        truncate(job.prompt(), JOB_PROMPT_PREVIEW));
            }
        }
        shutdown(0);
    }

    private String formatInstant(Instant instant) {
        var zoneId = scheduleProperties != null ? scheduleProperties.zoneId() : java.time.ZoneId.systemDefault();
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                .format(instant.atZone(zoneId));
    }

    private void executeBriefing() {
        var brief = layeredContext.briefing();
        if (cliQuiet()) {
            if (brief.isBlank()) {
                out("(no memories yet — mine documents with --mine first)");
            } else {
                out(brief);
            }
            out("");
            out("Store: " + pageStore.size() + " pages indexed.");
            out("Briefing length: " + brief.length() + " chars (~" + (brief.length() / 4) + " tokens).");
        } else {
            log.info("Briefing ({} chars):\n{}", brief.length(), brief);
            log.info("Store size: {} pages", pageStore.size());
        }
        shutdown(0);
    }

    private boolean cliQuiet() {
        return List.of(environment.getActiveProfiles()).contains("cli");
    }

    private void out(String line) {
        System.out.println(line);
    }

    private void err(String line) {
        System.err.println(line);
    }

    private void executeMine(ParsedArgs parsed) {
        if (parsed.paths().isEmpty()) {
            err("No paths provided after --mine");
            shutdown(1);
            return;
        }

        if (!cliQuiet()) {
            log.info("Mining {} path(s) into shelf '{}'", parsed.paths().size(), parsed.shelfName());
        }
        var shelf = new Shelf(parsed.shelfName());
        int totalPages = 0;
        var quiet = cliQuiet();

        for (var path : parsed.paths()) {
            int pages = minePath(path, shelf, quiet);
            totalPages += pages;
            if (quiet) {
                out("  " + path + " → " + pages + " pages");
            }
        }

        if (quiet) {
            out("");
            out("Shelf \"" + parsed.shelfName() + "\": " + totalPages + " pages total.");
        } else {
            log.info("Done. Total pages mined: {}", totalPages);
        }
        shutdown(0);
    }

    private int minePath(Path path, Shelf shelf, boolean quiet) {
        if (Files.isDirectory(path)) {
            int pages = miner.mineDirectory(path, shelf);
            if (!quiet) {
                log.info("  {} -> {} pages", path, pages);
            }
            return pages;
        }
        if (Files.isRegularFile(path)) {
            int pages = miner.mineFile(path, shelf);
            if (!quiet) {
                log.info("  {} -> {} pages", path, pages);
            }
            return pages;
        }
        if (!quiet) {
            log.warn("  {} -> skipped (not found)", path);
        }
        return 0;
    }

    private void executeSearch(ParsedArgs parsed) {
        if (parsed.query() == null || parsed.query().isBlank()) {
            err("No query provided after --search");
            shutdown(1);
            return;
        }

        var maxResults = parsed.maxResults() > 0 ? parsed.maxResults() : DEFAULT_MAX_RESULTS;
        var searchStart = System.currentTimeMillis();
        var results = parsed.shelfName() != null
                ? hybridSearcher.search(parsed.query(), maxResults, parsed.shelfName())
                : hybridSearcher.search(parsed.query(), maxResults);
        var searchMs = System.currentTimeMillis() - searchStart;
        log.debug("hybrid search query=\"{}\" results={} took={}ms",
                parsed.query(), results.size(), searchMs);

        if (cliQuiet()) {
            out("");
            out("Query: " + parsed.query());
            if (parsed.shelfName() != null) {
                out("Shelf: " + parsed.shelfName());
            }
            out("");
            if (results.isEmpty()) {
                out("No matching pages.");
            } else {
                for (int i = 0; i < results.size(); i++) {
                    var result = results.get(i);
                    var page = result.page();
                    out("— Result " + (i + 1) + " — score " + "%.4f".formatted(result.score()));
                    out("  Source: " + page.book().sourcePath());
                    out("  Shelf:  " + page.book().shelf().name());
                    out("");
                    out(wrapForDisplay(truncate(page.content(), SEARCH_SNIPPET_CHARS), 78));
                    out("");
                }
            }
            out("Store: " + pageStore.size() + " pages indexed.");
        } else {
            if (results.isEmpty()) {
                log.info("No results found for: {}", parsed.query());
            } else {
                log.info("Found {} result(s) for: {}", results.size(), parsed.query());
                for (int i = 0; i < results.size(); i++) {
                    var result = results.get(i);
                    var page = result.page();
                    log.info("  [{}] score={} shelf={} source={}",
                            i + 1,
                            "%.4f".formatted(result.score()),
                            page.book().shelf().name(),
                            page.book().sourcePath());
                    log.info("       {}", truncate(page.content(), 200));
                }
            }
            log.info("Store size: {} pages", pageStore.size());
        }
        shutdown(0);
    }

    private static String wrapForDisplay(String text, int width) {
        var words = text.replaceAll("\\s+", " ").trim().split(" ");
        var sb = new StringBuilder();
        var lineLen = 0;
        for (var word : words) {
            if (lineLen + word.length() + (lineLen > 0 ? 1 : 0) > width && lineLen > 0) {
                sb.append('\n');
                lineLen = 0;
            }
            if (lineLen > 0) {
                sb.append(' ');
                lineLen++;
            }
            sb.append(word);
            lineLen += word.length();
        }
        return sb.toString();
    }

    private void executeRemoveShelf(ParsedArgs parsed) {
        if (parsed.shelfName() == null) {
            err("No shelf name provided. Usage: --remove-shelf <name>");
            shutdown(1);
            return;
        }

        int removed = pageStore.deleteByShelf(parsed.shelfName());
        if (cliQuiet()) {
            out("Removed " + removed + " pages from shelf \"" + parsed.shelfName() + "\".");
            out("Store: " + pageStore.size() + " pages remaining.");
        } else {
            log.info("Removed {} pages from shelf '{}'", removed, parsed.shelfName());
            log.info("Store size: {} pages", pageStore.size());
        }
        shutdown(0);
    }

    private void executeExportKg(ParsedArgs parsed) {
        var format = parsed.format() == null ? "cypher" : parsed.format();
        if (!List.of("cypher", "csv", "tsv").contains(format)) {
            err("Unknown --format '" + format + "'. Use cypher, csv, or tsv.");
            shutdown(1);
            return;
        }

        var triples = knowledgeGraph.openTriples();
        var referencedIds = triples.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.subject(), t.object()))
                .collect(java.util.stream.Collectors.toSet());
        var entities = knowledgeGraph.allEntities().stream()
                .filter(e -> referencedIds.contains(e.id()))
                .toList();

        if (entities.isEmpty() && triples.isEmpty()) {
            if (cliQuiet()) {
                out("(knowledge graph is empty — mine some documents first)");
            } else {
                log.info("Knowledge graph is empty — nothing to export.");
            }
            shutdown(0);
            return;
        }

        var defaultDir = Path.of(System.getProperty("user.home"), ".recall", "export");
        try {
            if ("cypher".equals(format)) {
                var outputPath = parsed.paths().isEmpty()
                        ? defaultDir.resolve("kg.cypher")
                        : parsed.paths().getFirst();
                writeFile(outputPath, renderCypher(entities, triples));
                reportExport(entities.size(), triples.size(), format, List.of(outputPath));
            } else {
                var outputDir = parsed.paths().isEmpty() ? defaultDir : parsed.paths().getFirst();
                Files.createDirectories(outputDir);
                var separator = "csv".equals(format) ? "," : "\t";
                var nodesPath = outputDir.resolve("nodes." + format);
                var edgesPath = outputDir.resolve("edges." + format);
                Files.writeString(nodesPath, renderNodesTable(entities, separator, format));
                Files.writeString(edgesPath, renderEdgesTable(triples, separator, format));
                reportExport(entities.size(), triples.size(), format, List.of(nodesPath, edgesPath));
            }
        } catch (IOException e) {
            err("Failed to write export: " + e.getMessage());
            shutdown(1);
            return;
        }
        shutdown(0);
    }

    private void executeExportMd(ParsedArgs parsed) {
        var defaultDir = Path.of(System.getProperty("user.home"), ".recall", "export");
        var outputDir = parsed.paths().isEmpty() ? defaultDir : parsed.paths().getFirst();

        var pages = pageStore.recent(Integer.MAX_VALUE);
        int written;
        try {
            written = bookExporter.export(pages, outputDir, parsed.shelfName());
        } catch (IOException e) {
            err("Failed to write export: " + e.getMessage());
            shutdown(1);
            return;
        }

        if (cliQuiet()) {
            if (written == 0) {
                var qualifier = parsed.shelfName() != null
                        ? " on shelf \"" + parsed.shelfName() + "\""
                        : "";
                out("(no books to export" + qualifier + ")");
            } else {
                out("Exported " + written + " book(s) to " + outputDir);
            }
        } else {
            log.info("Exported {} book(s) to {}", written, outputDir);
        }
        shutdown(0);
    }

    private static void writeFile(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }

    private void reportExport(int entityCount, int tripleCount, String format, List<Path> outputs) {
        if (cliQuiet()) {
            out("Exported " + entityCount + " entities and " + tripleCount
                    + " triples (" + format + ") to:");
            for (var p : outputs) {
                out("  " + p);
            }
            out("");
            switch (format) {
                case "cypher" -> {
                    out("Load into Neo4j Desktop:");
                    out("  1. Start a database in Neo4j Desktop and open Neo4j Browser.");
                    out("  2. Drop the file onto the browser window, or in the Browser run:");
                    out("     :source " + outputs.getFirst());
                    out("  3. Execute the statements (play button runs the current one).");
                    out("");
                    out("Or from a shell:");
                    out("  cat " + outputs.getFirst() + " | cypher-shell -u neo4j -p <password>");
                }
                case "csv", "tsv" -> {
                    var dir = outputs.getFirst().getParent();
                    out("Load into Neo4j (copy files into the DB's import/ directory first):");
                    out("  LOAD CSV WITH HEADERS FROM 'file:///nodes." + format + "' AS row"
                            + ("tsv".equals(format) ? " FIELDTERMINATOR '\\t'" : ""));
                    out("  MERGE (e:Entity {id: row.id}) SET e.name = row.name, e.type = row.type;");
                    out("");
                    out("  LOAD CSV WITH HEADERS FROM 'file:///edges." + format + "' AS row"
                            + ("tsv".equals(format) ? " FIELDTERMINATOR '\\t'" : ""));
                    out("  MATCH (s:Entity {id: row.source}), (o:Entity {id: row.target})");
                    out("  MERGE (s)-[r:RELATES {predicate: row.predicate}]->(o)");
                    out("  SET r.validFrom = row.validFrom, r.validTo = row.validTo,"
                            + " r.confidence = toFloat(row.confidence), r.sourcePageId = row.sourcePageId;");
                    out("");
                    out("Or open " + dir + " in Gephi (File → Import spreadsheet) — the");
                    out("  'source'/'target' columns on edges." + format + " are auto-detected.");
                }
            }
        } else {
            log.info("Exported {} entities / {} triples ({}): {}",
                    entityCount, tripleCount, format, outputs);
        }
    }

    static String renderNodesTable(List<Entity> entities, String separator, String format) {
        var sb = new StringBuilder();
        sb.append(String.join(separator, "id", "name", "type")).append('\n');
        for (var e : entities) {
            var type = e.type() == null ? "unknown" : e.type();
            sb.append(escapeField(e.id(), format)).append(separator)
                    .append(escapeField(e.name(), format)).append(separator)
                    .append(escapeField(type, format)).append('\n');
        }
        return sb.toString();
    }

    static String renderEdgesTable(List<Triple> triples, String separator, String format) {
        var sb = new StringBuilder();
        sb.append(String.join(separator,
                "source", "target", "predicate",
                "validFrom", "validTo", "confidence", "sourcePageId")).append('\n');
        for (var t : triples) {
            sb.append(escapeField(t.subject(), format)).append(separator)
                    .append(escapeField(t.object(), format)).append(separator)
                    .append(escapeField(t.predicate(), format)).append(separator)
                    .append(escapeField(asString(t.validFrom()), format)).append(separator)
                    .append(escapeField(asString(t.validTo()), format)).append(separator)
                    .append(Double.toString(t.confidence())).append(separator)
                    .append(escapeField(t.sourcePageId(), format)).append('\n');
        }
        return sb.toString();
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    static String escapeField(String value, String format) {
        if (value == null) return "";
        if ("tsv".equals(format)) {
            return value.replace("\\", "\\\\")
                    .replace("\t", "\\t")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
        var needsQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String renderCypher(List<Entity> entities, List<Triple> triples) {
        var sb = new StringBuilder();
        sb.append("// BrainJar knowledge graph export — ").append(Instant.now()).append('\n');
        sb.append("// Entities: ").append(entities.size())
                .append("   Triples: ").append(triples.size()).append('\n');
        sb.append("// Re-running this file will clear all existing nodes/relationships first.\n\n");

        sb.append("// --- Clear all ---\n");
        sb.append("MATCH (n) DETACH DELETE n;\n\n");

        sb.append("CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE;\n\n");

        if (!entities.isEmpty()) {
            sb.append("// --- Entities ---\n");
            sb.append("UNWIND [\n");
            for (int i = 0; i < entities.size(); i++) {
                var e = entities.get(i);
                var type = e.type() == null ? "unknown" : e.type();
                sb.append("  {id: '").append(cypherEscape(e.id())).append("'")
                        .append(", name: '").append(cypherEscape(e.name())).append("'")
                        .append(", type: '").append(cypherEscape(type)).append("'}");
                if (i < entities.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append("] AS row\n")
                    .append("MERGE (e:Entity {id: row.id}) SET e.name = row.name, e.type = row.type;\n\n");
        }

        if (!triples.isEmpty()) {
            var byPredicate = triples.stream().collect(Collectors.groupingBy(
                    Triple::predicate, LinkedHashMap::new, Collectors.toList()));

            sb.append("// --- Relationships (one UNWIND per predicate so rel type == predicate) ---\n");
            for (var entry : byPredicate.entrySet()) {
                var relType = toRelType(entry.getKey());
                sb.append("UNWIND [\n");
                var rows = entry.getValue();
                for (int i = 0; i < rows.size(); i++) {
                    var t = rows.get(i);
                    sb.append("  {s: '").append(cypherEscape(t.subject())).append("'")
                            .append(", o: '").append(cypherEscape(t.object())).append("'")
                            .append(", validFrom: ").append(quoteOrNull(t.validFrom()))
                            .append(", validTo: ").append(quoteOrNull(t.validTo()))
                            .append(", confidence: ").append(t.confidence())
                            .append(", sourcePageId: ").append(quoteOrNull(t.sourcePageId()))
                            .append("}");
                    if (i < rows.size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append("] AS row\n")
                        .append("MATCH (s:Entity {id: row.s}), (o:Entity {id: row.o})\n")
                        .append("MERGE (s)-[r:").append(relType).append("]->(o)\n")
                        .append("SET r.validFrom = row.validFrom, r.validTo = row.validTo, ")
                        .append("r.confidence = row.confidence, r.sourcePageId = row.sourcePageId;\n\n");
            }
        }
        return sb.toString();
    }

    private static String cypherEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String quoteOrNull(Object value) {
        return value == null ? "null" : "'" + cypherEscape(value.toString()) + "'";
    }

    static String toRelType(String predicate) {
        if (predicate == null || predicate.isBlank()) return "RELATES";
        var cleaned = predicate.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (cleaned.isBlank()) return "RELATES";
        if (!Character.isLetter(cleaned.charAt(0))) {
            cleaned = "R_" + cleaned;
        }
        return cleaned;
    }

    private void shutdown(int exitCode) {
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    static String truncate(String text, int maxLen) {
        var singleLine = text.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxLen) {
            return singleLine;
        }
        return singleLine.substring(0, maxLen) + "...";
    }

    enum Command { NONE, MINE, SEARCH, REMOVE_SHELF, BRIEFING, LIST_SHELVES, LATEST, LIST_JOBS, EXPORT_KG, EXPORT_MD, REMINE }

    record ParsedArgs(Command command, List<Path> paths, String shelfName, String query, int maxResults, String format) {}

    static ParsedArgs parseArgs(String[] args) {
        var command = Command.NONE;
        var paths = new ArrayList<Path>();
        String shelfName = null;
        String query = null;
        String format = null;
        int maxResults = 0;
        boolean collectingPaths = false;

        for (int i = 0; i < args.length; i++) {
            if ("--spring.profiles.active".equals(args[i]) && i + 1 < args.length) {
                i++;
                continue;
            }
            if (args[i].startsWith("--spring.profiles.active=")) {
                continue;
            }
            switch (args[i]) {
                case "--mine" -> {
                    command = Command.MINE;
                    collectingPaths = true;
                }
                case "--search" -> {
                    command = Command.SEARCH;
                    collectingPaths = false;
                    var queryParts = new ArrayList<String>();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        queryParts.add(args[++i]);
                    }
                    if (!queryParts.isEmpty()) {
                        query = String.join(" ", queryParts);
                    }
                }
                case "--remove-shelf" -> {
                    command = Command.REMOVE_SHELF;
                    collectingPaths = false;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        shelfName = args[++i];
                    }
                }
                case "--briefing" -> {
                    command = Command.BRIEFING;
                    collectingPaths = false;
                }
                case "--list-shelves" -> {
                    command = Command.LIST_SHELVES;
                    collectingPaths = false;
                }
                case "--latest" -> {
                    command = Command.LATEST;
                    collectingPaths = false;
                }
                case "--list-jobs" -> {
                    command = Command.LIST_JOBS;
                    collectingPaths = false;
                }
                case "--export-kg" -> {
                    command = Command.EXPORT_KG;
                    collectingPaths = true;
                }
                case "--export-md" -> {
                    command = Command.EXPORT_MD;
                    collectingPaths = true;
                }
                case "--remine" -> {
                    command = Command.REMINE;
                    collectingPaths = false;
                }
                case "--shelf" -> {
                    collectingPaths = false;
                    if (i + 1 < args.length) {
                        shelfName = args[++i];
                    }
                }
                case "--max", "-n" -> {
                    if (i + 1 < args.length) {
                        maxResults = Integer.parseInt(args[++i]);
                    }
                }
                case "--format" -> {
                    if (i + 1 < args.length) {
                        format = args[++i].toLowerCase();
                    }
                }
                default -> {
                    if (collectingPaths && !args[i].startsWith("--")) {
                        paths.add(Path.of(args[i]));
                    }
                }
            }
        }

        if (command == Command.MINE && shelfName == null && !paths.isEmpty()) {
            shelfName = deriveShelfName(paths.getFirst());
        }
        if (command == Command.MINE && shelfName == null) {
            shelfName = "general";
        }

        return new ParsedArgs(command, List.copyOf(paths), shelfName, query, maxResults, format);
    }

    private static String deriveShelfName(Path path) {
        return Optional.ofNullable(path.getFileName())
                .map(Path::toString)
                .orElse("general");
    }
}
