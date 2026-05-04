package brainjar.recall.store;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;

import java.util.List;
import java.util.Optional;

public interface PageStore {

    void store(List<Page> pages);

    List<SearchResult> search(String query, int maxResults);

    List<SearchResult> search(String query, int maxResults, String shelfName);

    /**
     * Most recently modified pages, newest first, capped at {@code limit}.
     * Ordered by {@link Page#book()} {@code lastModified} descending.
     */
    List<Page> recent(int limit);

    /**
     * Most recently modified pages on a specific shelf, newest first, capped
     * at {@code limit}. Ordered by {@link Page#book()} {@code lastModified}
     * descending. Useful when you want to browse a shelf without ranking by
     * vector similarity (e.g. "show me my wines" rather than "wines that are
     * semantically similar to the word 'wines'").
     */
    List<Page> recentByShelf(String shelfName, int limit);

    void deleteByBook(Book book);

    int deleteByShelf(String shelfName);

    /**
     * Delete a single page by its id. No-op if the page is not present.
     */
    void deletePage(String pageId);

    /**
     * Look up a page by its id. Returns empty if no page with that id exists.
     */
    Optional<Page> findById(String pageId);

    /**
     * The next available chunk index for the given book, i.e. one greater than
     * the highest existing {@link Page#chunkIndex()} for that book, or {@code 0}
     * if the book has no pages yet. Useful for appending captured memories to a
     * daily book without colliding with existing page ids.
     */
    int nextChunkIndex(Book book);

    int size();
}
