package brainjar.recall.kg;

import java.time.Instant;

public record Entity(String id, String name, String type, Instant createdAt) {

    /**
     * Canonicalize a display name into a stable id: lowercase, strip
     * punctuation, collapse runs of whitespace into a single underscore,
     * trim leading/trailing underscores.
     *
     * <p>Kept intentionally small — this is the "tiny entity resolution"
     * layer. It handles case drift, stray punctuation ("Acme, Inc." →
     * {@code acme_inc}) and whitespace variance, not aliases or plurals.
     * A proper alias table layered on top of {@code entities.canonical_id}
     * is the escape hatch for anything more ambitious.
     */
    public static String normalizeId(String name) {
        if (name == null) {
            return "";
        }
        var lowered = name.toLowerCase();
        var sb = new StringBuilder(lowered.length());
        boolean lastWasSeparator = true;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                lastWasSeparator = false;
            } else if (c == '\'' || c == '\u2019') {
                // Drop apostrophes (straight and curly) without introducing a separator:
                // "O'Brien" should become "obrien", not "o_brien".
            } else if (!lastWasSeparator) {
                sb.append('_');
                lastWasSeparator = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
