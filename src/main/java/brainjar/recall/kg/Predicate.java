package brainjar.recall.kg;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Closed predicate vocabulary for the knowledge graph. Extractors emit
 * triples using these names; anything else is rejected at validation time.
 *
 * <p>Each predicate is either {@link Kind#FUNCTIONAL} (at most one open
 * value per subject — new facts supersede earlier ones) or
 * {@link Kind#MULTI_VALUED} (values coexist — e.g. a person can
 * {@code use} many tools at once).
 *
 * <p>Start small and extend only when rejections pile up. Rename with
 * care — bumping a predicate name is effectively a schema break and
 * should be paired with a version bump on the extractor and a remine.
 */
public final class Predicate {

    public enum Kind { FUNCTIONAL, MULTI_VALUED }

    public static final String MENTIONS = "mentions";
    public static final String WORKS_AT = "works_at";
    public static final String LOCATED_IN = "located_in";
    public static final String HAS_ROLE = "has_role";
    public static final String PREFERS = "prefers";
    public static final String OWNS = "owns";
    public static final String USES = "uses";
    public static final String CREATED = "created";
    public static final String PART_OF = "part_of";
    public static final String RELATED_TO = "related_to";

    private static final Map<String, Kind> REGISTRY = Map.ofEntries(
            Map.entry(MENTIONS, Kind.MULTI_VALUED),
            Map.entry(WORKS_AT, Kind.FUNCTIONAL),
            Map.entry(LOCATED_IN, Kind.FUNCTIONAL),
            Map.entry(HAS_ROLE, Kind.FUNCTIONAL),
            Map.entry(PREFERS, Kind.FUNCTIONAL),
            Map.entry(OWNS, Kind.MULTI_VALUED),
            Map.entry(USES, Kind.MULTI_VALUED),
            Map.entry(CREATED, Kind.MULTI_VALUED),
            Map.entry(PART_OF, Kind.MULTI_VALUED),
            Map.entry(RELATED_TO, Kind.MULTI_VALUED)
    );

    private Predicate() {}

    public static String normalize(String predicate) {
        if (predicate == null) {
            return "";
        }
        return predicate.toLowerCase().trim().replace(' ', '_').replace('-', '_');
    }

    public static boolean isKnown(String predicate) {
        return REGISTRY.containsKey(normalize(predicate));
    }

    public static Optional<Kind> kindOf(String predicate) {
        return Optional.ofNullable(REGISTRY.get(normalize(predicate)));
    }

    public static boolean isFunctional(String predicate) {
        return kindOf(predicate).map(k -> k == Kind.FUNCTIONAL).orElse(false);
    }

    public static Set<String> vocabulary() {
        return REGISTRY.keySet();
    }
}
