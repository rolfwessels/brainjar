package brainjar.recall.store;

import brainjar.recall.model.Summary;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store for {@link Summary} records keyed by page id.
 *
 * <p>Kept deliberately in-memory for now: {@code SummaryCompressor} is
 * deterministic and fast, so we prefer to regenerate on startup (when
 * pages are reloaded from {@code embeddings.json}) rather than persist a
 * separate file. Promote to SQLite if regeneration ever becomes a
 * bottleneck — the interface is the same.
 */
public class SummaryStore {

    private final ConcurrentMap<String, Summary> byPageId = new ConcurrentHashMap<>();

    public void put(Summary summary) {
        if (summary == null || summary.pageId() == null) {
            return;
        }
        byPageId.put(summary.pageId(), summary);
    }

    public Optional<Summary> get(String pageId) {
        return Optional.ofNullable(byPageId.get(pageId));
    }

    public void remove(String pageId) {
        byPageId.remove(pageId);
    }

    public Collection<Summary> all() {
        return byPageId.values();
    }

    public int size() {
        return byPageId.size();
    }
}
