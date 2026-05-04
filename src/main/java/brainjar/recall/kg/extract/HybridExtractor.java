package brainjar.recall.kg.extract;

import brainjar.recall.ingest.SummaryCompressor;
import brainjar.recall.model.Page;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link ExtractorSignals} on each page. Pages that cross the
 * threshold go through the {@link LlmExtractor}; everything else is
 * summary-only (no triples).
 *
 * <p>Intentionally does NOT fall back to {@link MentionsExtractor} for
 * low-signal pages — the whole point is to avoid re-polluting the graph
 * with generic {@code mentions} triples. Low signal means "keep it out
 * of the graph".
 */
public class HybridExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(HybridExtractor.class);

    private final SummaryCompressor summaryCompressor;
    private final LlmExtractor llmExtractor;

    public HybridExtractor(SummaryCompressor summaryCompressor, LlmExtractor llmExtractor) {
        this.summaryCompressor = summaryCompressor;
        this.llmExtractor = llmExtractor;
    }

    @Override
    public String version() {
        return llmExtractor.version();
    }

    @Override
    public ExtractionResult extract(Page page) {
        var summary = summaryCompressor.compress(page.content(), page.id());
        if (!ExtractorSignals.worthExtracting(summary, page.content())) {
            log.debug("Skipping LLM extraction for page {} — low signal score", page.id());
            return ExtractionResult.summaryOnly(summary);
        }
        return llmExtractor.extract(page);
    }
}
