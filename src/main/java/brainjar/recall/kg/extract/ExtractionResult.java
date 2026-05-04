package brainjar.recall.kg.extract;

import brainjar.recall.kg.Triple;
import brainjar.recall.model.Summary;

import java.util.List;

/**
 * Output of an {@link Extractor} run: a lightweight page summary plus any
 * typed triples worth asserting into the knowledge graph.
 *
 * <p>Design rule: {@link #summary()} is always present, even for pages that
 * don't cross the signal threshold. {@link #triples()} may be empty — that's
 * how we keep the KG from accumulating half-facts for low-signal pages.
 */
public record ExtractionResult(Summary summary, List<Triple> triples) {

    public ExtractionResult {
        triples = List.copyOf(triples);
    }

    public static ExtractionResult summaryOnly(Summary summary) {
        return new ExtractionResult(summary, List.of());
    }
}
