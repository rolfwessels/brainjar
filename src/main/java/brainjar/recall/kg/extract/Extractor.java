package brainjar.recall.kg.extract;

import brainjar.recall.model.Page;

/**
 * Turns a {@link Page} into an {@link ExtractionResult}: a summary (always)
 * and, when the page is informative enough, a list of typed triples for the
 * knowledge graph.
 *
 * <p>Implementations must be stateless and safe to call from multiple threads.
 * They must also be deterministic given the same page content — the
 * extraction queue short-circuits re-runs by comparing {@code (pageId,
 * extractorVersion, contentHash)}, and that only works if re-running
 * produces the same output.
 */
public interface Extractor {

    /**
     * A short stable identifier for this extractor's output shape. Bump it
     * when the predicate vocabulary, prompt, or extraction logic changes
     * meaningfully — downstream code uses the version to decide whether
     * stored triples are stale and need re-running.
     */
    String version();

    ExtractionResult extract(Page page);
}
