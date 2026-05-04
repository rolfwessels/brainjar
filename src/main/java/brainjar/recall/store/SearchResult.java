package brainjar.recall.store;

import brainjar.recall.model.Page;

public record SearchResult(Page page, double score) {
}
