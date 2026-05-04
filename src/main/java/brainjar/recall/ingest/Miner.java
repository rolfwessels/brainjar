package brainjar.recall.ingest;

import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.extract.ExtractionResult;
import brainjar.recall.kg.extract.Extractor;
import brainjar.recall.kg.extract.MentionsExtractor;
import brainjar.recall.kg.extract.async.ExtractionQueue;
import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SummaryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class Miner {

    private static final Logger log = LoggerFactory.getLogger(Miner.class);

    private final Chunker chunker;
    private final Extractor extractor;
    private final PageStore pageStore;
    private final SummaryStore summaryStore;
    private final KnowledgeGraph knowledgeGraph;

    public Miner(Chunker chunker, PageStore pageStore) {
        this(chunker, new MentionsExtractor(), pageStore, new SummaryStore(), null);
    }

    public Miner(Chunker chunker,
                 SummaryCompressor summaryCompressor,
                 PageStore pageStore,
                 KnowledgeGraph knowledgeGraph) {
        this(chunker, new MentionsExtractor(summaryCompressor), pageStore, new SummaryStore(), knowledgeGraph);
    }

    public Miner(Chunker chunker,
                 SummaryCompressor summaryCompressor,
                 PageStore pageStore,
                 SummaryStore summaryStore,
                 KnowledgeGraph knowledgeGraph) {
        this(chunker, new MentionsExtractor(summaryCompressor), pageStore, summaryStore, knowledgeGraph);
    }

    public Miner(Chunker chunker,
                 Extractor extractor,
                 PageStore pageStore,
                 SummaryStore summaryStore,
                 KnowledgeGraph knowledgeGraph) {
        this.chunker = chunker;
        this.extractor = extractor;
        this.pageStore = pageStore;
        this.summaryStore = summaryStore;
        this.knowledgeGraph = knowledgeGraph;
    }

    public int mineFile(Path file, Shelf shelf) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        var content = readFile(file);
        if (content.isBlank()) {
            return 0;
        }
        var book = createBook(file, shelf);
        pageStore.deleteByBook(book);
        var pages = chunker.chunk(content, book);
        if (pages.isEmpty()) {
            return 0;
        }

        pageStore.store(pages);
        enrich(pages);
        return pages.size();
    }

    public int mineDirectory(Path directory, Shelf shelf) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        return listFiles(directory).stream()
                .mapToInt(file -> mineFile(file, shelf))
                .sum();
    }

    /**
     * Enqueue every page currently in the store for re-extraction. Use
     * after the extractor version changes, or when you want to pull user
     * memories (which bypass {@link #mineFile}) into the knowledge graph.
     *
     * <p>Idempotency is enforced downstream: {@code extraction_state} makes
     * re-enqueueing an unchanged page at the same version a no-op.
     *
     * @return number of pages successfully enqueued.
     */
    public int remineAll(ExtractionQueue queue) {
        if (queue == null) {
            return 0;
        }
        int enqueued = 0;
        for (var page : pageStore.recent(Integer.MAX_VALUE)) {
            if (queue.enqueue(page.id())) {
                enqueued++;
            }
        }
        log.info("remineAll enqueued {} page(s) for extraction", enqueued);
        return enqueued;
    }

    private void enrich(List<Page> pages) {
        for (var page : pages) {
            ExtractionResult result;
            try {
                result = extractor.extract(page);
            } catch (RuntimeException e) {
                log.warn("Extractor failed for page {}: {}", page.id(), e.getMessage());
                continue;
            }
            summaryStore.put(result.summary());
            if (knowledgeGraph != null && !result.triples().isEmpty()) {
                for (var triple : result.triples()) {
                    knowledgeGraph.addTriple(
                            triple.subject(),
                            triple.predicate(),
                            triple.object(),
                            triple.validFrom(),
                            triple.confidence(),
                            triple.sourcePageId()
                    );
                }
            }
        }
    }

    private Book createBook(Path file, Shelf shelf) {
        try {
            var lastModified = Files.getLastModifiedTime(file).toInstant();
            return new Book(file, file.getFileName().toString(), shelf, lastModified);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (MalformedInputException e) {
            log.warn("Skipping non-text file: {}", file);
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> listFiles(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
