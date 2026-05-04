package brainjar.recall;

/**
 * A single memory to store in a batch {@code rememberMany} call. Each item
 * carries its own shelf so a mixed list can land across multiple shelves in
 * one tool invocation.
 */
public record MemoryItem(String shelf, String content) {
}
