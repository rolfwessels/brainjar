package brainjar.recall.search;

import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.Triple;
import brainjar.recall.store.PageStore;
import brainjar.recall.store.SearchResult;

import java.time.LocalDate;
import java.util.List;

public class Searcher {

    private final PageStore pageStore;
    private final KnowledgeGraph knowledgeGraph;

    public Searcher(PageStore pageStore, KnowledgeGraph knowledgeGraph) {
        this.pageStore = pageStore;
        this.knowledgeGraph = knowledgeGraph;
    }

    public Searcher(PageStore pageStore) {
        this(pageStore, null);
    }

    public List<SearchResult> search(String query, int maxResults) {
        return pageStore.search(query, maxResults);
    }

    public List<SearchResult> search(String query, int maxResults, String shelfName) {
        return pageStore.search(query, maxResults, shelfName);
    }

    public List<Triple> findRelatedFacts(String subjectName) {
        if (knowledgeGraph == null) {
            return List.of();
        }
        return knowledgeGraph.query(subjectName);
    }

    public List<Triple> findRelatedFacts(String subjectName, LocalDate asOf) {
        if (knowledgeGraph == null) {
            return List.of();
        }
        return knowledgeGraph.queryAsOf(subjectName, asOf);
    }
}
