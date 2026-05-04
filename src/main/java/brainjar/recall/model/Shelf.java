package brainjar.recall.model;

public record Shelf(String name) {

    public Shelf {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Shelf name must not be blank");
        }
    }
}
