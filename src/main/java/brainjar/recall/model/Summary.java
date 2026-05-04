package brainjar.recall.model;

import java.util.List;

public record Summary(
        String pageId,
        List<String> entities,
        List<String> topics,
        String keySentence,
        List<String> flags
) {

    public Summary {
        entities = List.copyOf(entities);
        topics = List.copyOf(topics);
        flags = List.copyOf(flags);
    }

    public boolean isEmpty() {
        return entities.isEmpty() && topics.isEmpty()
                && (keySentence == null || keySentence.isBlank()) && flags.isEmpty();
    }
}
