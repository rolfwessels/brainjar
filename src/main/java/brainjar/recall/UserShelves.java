package brainjar.recall;

/**
 * Single source of truth for translating between Perry-facing display shelf
 * names ({@code "wines"}) and storage shelf names ({@code "user:<UID>:wines"}).
 *
 * <p>The {@code user:<UID>:} prefix is purely a storage detail used to keep
 * each user's captures separate from each other and from globally mined
 * shelves like {@code "docs"}. Perry should never see, type, or reason about
 * the prefix; it is added on the way into the store and stripped on the way
 * out by callers (tools, briefings, search formatters).
 */
public final class UserShelves {

    static final String PREFIX = "user:";
    static final String SEP = ":";
    private static final String DEFAULT_DISPLAY = "notes";

    private UserShelves() {
    }

    /**
     * Translate a display shelf name like {@code "wines"} into the storage
     * shelf name for the given user, e.g. {@code "user:42:wines"}. The display
     * name is normalised first (see {@link #normalise(String)}).
     */
    public static String toStorage(String userId, String displayShelf) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return PREFIX + userId + SEP + normalise(displayShelf);
    }

    /**
     * Translate a stored shelf name back to what Perry should see.
     *
     * <ul>
     *   <li>If the shelf belongs to {@code userId}, strip the prefix.</li>
     *   <li>If the shelf is global (no {@code user:} prefix), return as-is.</li>
     *   <li>If the shelf belongs to <em>another</em> user, return the raw
     *       storage name. Defensive: callers should already have filtered
     *       these out before display.</li>
     * </ul>
     */
    public static String toDisplay(String userId, String storageShelf) {
        if (storageShelf == null) {
            return "";
        }
        if (!isUserScoped(storageShelf)) {
            return storageShelf;
        }
        if (userId != null) {
            var ownPrefix = PREFIX + userId + SEP;
            if (storageShelf.startsWith(ownPrefix)) {
                return storageShelf.substring(ownPrefix.length());
            }
        }
        return storageShelf;
    }

    /**
     * True iff {@code storageShelf} belongs to the given user (i.e. starts
     * with {@code "user:<userId>:"}).
     */
    public static boolean isOwnedBy(String userId, String storageShelf) {
        if (userId == null || storageShelf == null) {
            return false;
        }
        return storageShelf.startsWith(PREFIX + userId + SEP);
    }

    /**
     * True iff {@code storageShelf} is namespaced to <em>some</em> user
     * (starts with {@code "user:"}). Useful to distinguish global mined
     * shelves from per-user capture shelves.
     */
    public static boolean isUserScoped(String storageShelf) {
        return storageShelf != null && storageShelf.startsWith(PREFIX);
    }

    /**
     * True iff {@code storageShelf} is either owned by {@code userId} or is a
     * global shelf (anything not under {@code user:}). Used to filter what
     * Perry is allowed to see when reading.
     */
    public static boolean isVisibleTo(String userId, String storageShelf) {
        return !isUserScoped(storageShelf) || isOwnedBy(userId, storageShelf);
    }

    /**
     * Lowercase + restrict to {@code [a-z0-9-]}. Falls back to
     * {@code "notes"} when the input is null/blank/all-noise.
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DISPLAY;
        }
        var cleaned = raw.strip().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        cleaned = cleaned.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return cleaned.isBlank() ? DEFAULT_DISPLAY : cleaned;
    }
}
