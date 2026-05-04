package brainjar.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an assistant response into Discord-sendable chunks, or decides
 * that the response should be delivered as a file attachment instead.
 *
 * <p>Discord has a hard 2000-char limit per message; exceeding it throws
 * in JDA before the network call. This class is the safety net so we
 * never hit that exception, regardless of what the LLM produces.
 *
 * <p>Strategy (in priority order):
 * <ol>
 *   <li>If the response starts with {@code <!--file:name.md-->}, everything
 *       after the marker is uploaded as that filename. Perry emits this when
 *       asked to produce a structured document.</li>
 *   <li>If the response fits in {@link #SAFE_CHUNK_LENGTH}, send as one
 *       message.</li>
 *   <li>If the response contains explicit {@code <!--split-->} markers,
 *       split on those. Perry emits these at natural breaks in long prose.</li>
 *   <li>If the response is absurdly long ({@link #AUTO_FILE_THRESHOLD}) and
 *       has no markers, auto-upload as {@code response.md} — a safety net for
 *       runaway completions.</li>
 *   <li>Otherwise split on paragraph → line → sentence → hard-cut boundaries.</li>
 * </ol>
 *
 * <p>A second pass validates that every chunk produced by marker-splitting
 * is still under the limit; any that aren't fall through to boundary
 * splitting.
 */
public final class MessageSplitter {

    /** Discord's hard limit. */
    static final int DISCORD_MAX_LENGTH = 2000;

    /** Leave a small buffer in case of invisible joiners, zero-width chars, etc. */
    static final int SAFE_CHUNK_LENGTH = 1900;

    /** Above this, a markerless response is uploaded as a file rather than split. */
    static final int AUTO_FILE_THRESHOLD = 6000;

    static final String SPLIT_MARKER = "<!--split-->";
    private static final String DEFAULT_AUTO_FILENAME = "response.md";

    private static final Pattern FILE_DIRECTIVE = Pattern.compile(
            "^\\s*<!--\\s*file\\s*:\\s*([^\\s>]+?)\\s*-->\\s*\\r?\\n?");
    private static final Pattern FILENAME_SAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern HAS_EXTENSION = Pattern.compile(".+\\.[A-Za-z0-9]{1,8}$");

    private MessageSplitter() {
    }

    public sealed interface Result permits Messages, FileAttachment {
    }

    public record Messages(List<String> chunks) implements Result {
    }

    public record FileAttachment(String filename, String content) implements Result {
    }

    public static Result split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Messages(List.of(raw == null ? "" : raw));
        }

        var fileDirective = FILE_DIRECTIVE.matcher(raw);
        if (fileDirective.find()) {
            var filename = sanitiseFilename(fileDirective.group(1));
            var content = raw.substring(fileDirective.end());
            return new FileAttachment(filename, content);
        }

        if (raw.contains(SPLIT_MARKER)) {
            return new Messages(finalise(splitByMarker(raw)));
        }

        if (raw.length() <= SAFE_CHUNK_LENGTH) {
            return new Messages(List.of(raw));
        }

        if (raw.length() > AUTO_FILE_THRESHOLD) {
            return new FileAttachment(DEFAULT_AUTO_FILENAME, raw);
        }

        return new Messages(splitByBoundary(raw));
    }

    private static List<String> splitByMarker(String raw) {
        var parts = new ArrayList<String>();
        for (var piece : raw.split(Pattern.quote(SPLIT_MARKER), -1)) {
            var trimmed = piece.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /**
     * Second pass: any chunk still over the limit (because Perry didn't
     * mark it densely enough) is re-split by boundary.
     */
    private static List<String> finalise(List<String> chunks) {
        var out = new ArrayList<String>(chunks.size());
        for (var chunk : chunks) {
            if (chunk.length() <= SAFE_CHUNK_LENGTH) {
                out.add(chunk);
            } else {
                out.addAll(splitByBoundary(chunk));
            }
        }
        return out;
    }

    private static List<String> splitByBoundary(String raw) {
        var out = new ArrayList<String>();
        var remaining = raw;
        while (remaining.length() > SAFE_CHUNK_LENGTH) {
            int cut = findBoundary(remaining, SAFE_CHUNK_LENGTH);
            out.add(remaining.substring(0, cut).stripTrailing());
            remaining = remaining.substring(cut).stripLeading();
        }
        if (!remaining.isEmpty()) {
            out.add(remaining);
        }
        return out;
    }

    /**
     * Finds the best cut point at or below {@code limit}. Prefers paragraph
     * breaks, then line breaks, then sentence endings, then a hard cut.
     * Requires the boundary to be past the halfway mark so we don't
     * produce tiny leading chunks.
     */
    private static int findBoundary(String text, int limit) {
        int halfway = limit / 2;

        int paragraph = text.lastIndexOf("\n\n", limit);
        if (paragraph >= halfway) {
            return paragraph + 2;
        }
        int line = text.lastIndexOf('\n', limit);
        if (line >= halfway) {
            return line + 1;
        }
        int sentence = lastSentenceEnd(text, limit);
        if (sentence >= halfway) {
            return sentence;
        }
        return limit;
    }

    private static int lastSentenceEnd(String text, int limit) {
        int best = -1;
        int cap = Math.min(limit, text.length() - 1);
        for (int i = 0; i < cap; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i + 1))) {
                best = i + 2;
            }
        }
        return best;
    }

    private static String sanitiseFilename(String raw) {
        var name = FILENAME_SAFE.matcher(raw).replaceAll("-");
        if (name.isBlank() || name.equals("-")) {
            name = DEFAULT_AUTO_FILENAME;
        }
        if (!HAS_EXTENSION.matcher(name).matches()) {
            name += ".md";
        }
        return name;
    }
}
