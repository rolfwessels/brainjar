package brainjar.recall.kg.extract.async;

import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.extract.Extractor;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SummaryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Background worker that drains {@link ExtractionQueue}, runs the configured
 * {@link Extractor} over each page, and upserts the result into
 * {@link SummaryStore} + {@link KnowledgeGraph}.
 *
 * <p>Idempotency is enforced by the {@code extraction_state} table via
 * {@link KnowledgeGraph#upsertWithSupersession}: re-running the same
 * extractor over unchanged content short-circuits inside the transaction.
 *
 * <p>Retry policy: LLM errors are retried with exponential backoff. After
 * {@link #MAX_RETRIES} attempts we log and drop the job — the page will
 * be picked up again on the next startup rescan if the extractor_version
 * moved.
 */
public class ExtractionWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ExtractionWorker.class);
    private static final long POLL_TIMEOUT_MS = 500;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    private final ExtractionQueue queue;
    private final PageStore pageStore;
    private final SummaryStore summaryStore;
    private final KnowledgeGraph knowledgeGraph;
    private final Extractor extractor;

    private volatile boolean running = false;
    private Thread thread;
    private volatile int totalQueued = 0;
    private final java.util.concurrent.atomic.AtomicInteger processed =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public ExtractionWorker(ExtractionQueue queue,
                            PageStore pageStore,
                            SummaryStore summaryStore,
                            KnowledgeGraph knowledgeGraph,
                            Extractor extractor) {
        this.queue = queue;
        this.pageStore = pageStore;
        this.summaryStore = summaryStore;
        this.knowledgeGraph = knowledgeGraph;
        this.extractor = extractor;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "extraction-worker");
        thread.setDaemon(true);
        thread.start();
        totalQueued = queue.pendingSize();
        log.info("ExtractionWorker started (extractor version={}) — {} page(s) queued",
                extractor.version(), totalQueued);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running) {
            String pageId = null;
            try {
                pageId = queue.takeBlocking(POLL_TIMEOUT_MS);
                if (pageId == null) {
                    if (totalQueued > 0 && queue.isIdle()) {
                        log.info("KG extraction complete — processed {} page(s)", processed.get());
                        totalQueued = 0;
                    }
                    continue;
                }
                processWithRetry(pageId);
                int done = processed.incrementAndGet();
                int remaining = queue.pendingSize() + queue.inFlight() - 1;
                if (done % 10 == 0 || remaining == 0) {
                    log.info("KG extraction progress: {}/{} pages done, {} remaining",
                            done, Math.max(done + remaining, totalQueued), Math.max(remaining, 0));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Unexpected error processing page {}: {}",
                        pageId, e.getMessage(), e);
            } finally {
                if (pageId != null) {
                    queue.markDone();
                }
            }
        }
        log.info("ExtractionWorker stopped");
    }

    private void processWithRetry(String pageId) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                process(pageId);
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_RETRIES) {
                    log.warn("Giving up on page {} after {} attempts: {}",
                            pageId, MAX_RETRIES, e.getMessage());
                    return;
                }
                log.debug("Extraction attempt {} failed for page {}: {} — retrying in {}ms",
                        attempt, pageId, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff *= 2;
            }
        }
    }

    void process(String pageId) {
        var found = pageStore.findById(pageId);
        if (found.isEmpty()) {
            log.debug("Skipping extraction for pageId={} — page no longer exists", pageId);
            return;
        }
        var page = found.get();
        var contentHash = sha256(page.content());

        var existing = knowledgeGraph.extractionStateFor(pageId);
        if (existing.isPresent()
                && extractor.version().equals(existing.get().extractorVersion())
                && contentHash.equals(existing.get().contentHash())) {
            log.debug("Skipping extraction for pageId={} — already extracted at version {}",
                    pageId, extractor.version());
            return;
        }

        var result = extractor.extract(page);
        summaryStore.put(result.summary());
        var outcome = knowledgeGraph.upsertWithSupersession(
                pageId, extractor.version(), contentHash, result.triples());
        if (outcome.shortCircuited()) {
            return;
        }
        log.info("Extraction pageId={} version={} inserted={} closedStale={} superseded={}",
                pageId, extractor.version(),
                outcome.inserted(), outcome.closedStale(), outcome.superseded());
    }

    static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
