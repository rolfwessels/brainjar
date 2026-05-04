package brainjar.recall.model;

import java.nio.file.Path;
import java.time.Instant;

public record Book(Path sourcePath, String title, Shelf shelf, Instant lastModified) {

    public Book {
        if (sourcePath == null) {
            throw new IllegalArgumentException("Source path must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title must not be blank");
        }
        if (shelf == null) {
            throw new IllegalArgumentException("Shelf must not be null");
        }
    }
}
