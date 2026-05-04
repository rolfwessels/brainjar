package brainjar.recall.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record Page(String id, String content, int chunkIndex, Book book) {

    public Page {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Page content must not be blank");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("Chunk index must not be negative");
        }
        if (book == null) {
            throw new IllegalArgumentException("Book must not be null");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Page ID must not be blank");
        }
    }

    public static String generateId(String sourcePath, int chunkIndex) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var input = sourcePath + "_" + chunkIndex;
            var hash = HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
            return "page_" + hash.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
