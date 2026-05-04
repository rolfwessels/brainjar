package brainjar.recall.kg.extract;

import brainjar.recall.ingest.SummaryCompressor;
import brainjar.recall.kg.Triple;
import brainjar.recall.model.Page;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Baseline extractor that preserves the legacy behaviour of {@code
 * Miner.populateKg}: every entity surfaced by {@link SummaryCompressor}
 * becomes a {@code (bookTitle, mentions, entity)} triple.
 *
 * <p>Kept as a fallback and as a reference for what "zero improvement"
 * looks like. Once the hybrid extractor is in place this exists mostly
 * for tests and for the CLI-mined corpus that has never seen anything
 * better.
 */
public class MentionsExtractor implements Extractor {

    static final String VERSION = "mentions-v0";
    private static final String MENTIONS_PREDICATE = "mentions";
    private static final double MINING_CONFIDENCE = 0.5;

    private final SummaryCompressor summaryCompressor;

    public MentionsExtractor() {
        this(new SummaryCompressor());
    }

    public MentionsExtractor(SummaryCompressor summaryCompressor) {
        this.summaryCompressor = summaryCompressor;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ExtractionResult extract(Page page) {
        var summary = summaryCompressor.compress(page.content(), page.id());
        if (summary.isEmpty() || summary.entities().isEmpty()) {
            return ExtractionResult.summaryOnly(summary);
        }

        var validFrom = resolveValidFrom(page);
        var subject = titleWithoutExtension(page.book().title());

        var triples = new ArrayList<Triple>();
        for (var entity : summary.entities()) {
            if (Objects.equals(subject.toLowerCase(), entity.toLowerCase())) {
                continue;
            }
            triples.add(new Triple(
                    null,
                    subject,
                    MENTIONS_PREDICATE,
                    entity,
                    validFrom,
                    null,
                    MINING_CONFIDENCE,
                    page.id()
            ));
        }

        return new ExtractionResult(summary, triples);
    }

    private static LocalDate resolveValidFrom(Page page) {
        var lastModified = page.book().lastModified();
        return lastModified != null
                ? lastModified.atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.now();
    }

    private static String titleWithoutExtension(String title) {
        int dot = title.lastIndexOf('.');
        return dot > 0 ? title.substring(0, dot) : title;
    }
}
