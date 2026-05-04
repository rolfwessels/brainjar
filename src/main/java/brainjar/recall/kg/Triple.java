package brainjar.recall.kg;

import java.time.LocalDate;

public record Triple(
        String id,
        String subject,
        String predicate,
        String object,
        LocalDate validFrom,
        LocalDate validTo,
        double confidence,
        String sourcePageId
) {

    /**
     * Back-compat shim. Delegates to {@link Predicate#normalize(String)} so
     * there is a single source of truth for how predicates are canonicalized
     * before they hit SQL.
     */
    public static String normalizePredicate(String predicate) {
        return Predicate.normalize(predicate);
    }
}
