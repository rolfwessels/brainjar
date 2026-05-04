package brainjar.recall;

import brainjar.context.UserContext;
import brainjar.recall.kg.KnowledgeGraph;
import brainjar.recall.kg.extract.async.ExtractionQueue;
import brainjar.recall.search.LayeredContext;
import brainjar.recall.search.Searcher;
import brainjar.recall.store.FakeEmbeddingModel;
import brainjar.recall.store.InMemoryPageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallToolTest {

    private static final String USER_ID = "user-42";

    private InMemoryPageStore pageStore;
    private KnowledgeGraph knowledgeGraph;
    private RecallTool tool;

    @BeforeEach
    void setUp() {
        pageStore = new InMemoryPageStore(new FakeEmbeddingModel());
        knowledgeGraph = new KnowledgeGraph("jdbc:sqlite::memory:");
        var searcher = new Searcher(pageStore, knowledgeGraph);
        var layeredContext = new LayeredContext(null, pageStore);
        tool = new RecallTool(searcher, layeredContext, pageStore, knowledgeGraph, new ExtractionQueue());
        UserContext.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        knowledgeGraph.close();
    }

    @Test
    void remember_ShouldStoreOnUserScopedShelf() {
        tool.remember("I prefer a slightly louder volume from now on.", "preferences");

        assertThat(pageStore.size()).isEqualTo(1);
        var result = pageStore.search("volume", 1).getFirst();
        assertThat(result.page().book().shelf().name()).isEqualTo("user:" + USER_ID + ":preferences");
        assertThat(result.page().book().sourcePath().toString())
                .contains("captures")
                .contains(USER_ID)
                .contains("preferences");
    }

    @Test
    void remember_TwiceOnSameDay_ShouldAppendInSameBook() {
        tool.remember("I use HotChocolate for GraphQL in C#.", "tech");
        tool.remember("We run AWS MSK for event streaming.", "tech");

        assertThat(pageStore.size()).isEqualTo(2);
        var firstBookPath = pageStore.search("HotChocolate", 1).getFirst().page().book().sourcePath();
        var secondBookPath = pageStore.search("MSK", 1).getFirst().page().book().sourcePath();
        assertThat(firstBookPath).isEqualTo(secondBookPath);
    }

    @Test
    void remember_ShouldUseDistinctChunkIndicesPerCapture() {
        tool.remember("First memory about deployment pipelines.", "tech");
        tool.remember("Second memory about observability stack.", "tech");

        var pages = pageStore.recent(10);
        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(p -> p.chunkIndex()).containsExactlyInAnyOrder(0, 1);
        assertThat(pages).extracting(p -> p.id()).doesNotHaveDuplicates();
    }

    @Test
    void remember_WhenBlankContent_ShouldBeNoOp() {
        var response = tool.remember("   ", "notes");

        assertThat(pageStore.size()).isZero();
        assertThat(response).containsIgnoringCase("nothing to remember");
    }

    @Test
    void remember_WhenBlankShelf_ShouldDefaultToNotes() {
        tool.remember("Something vaguely worth keeping.", "");

        var result = pageStore.search("vaguely worth keeping", 1).getFirst();
        assertThat(result.page().book().shelf().name()).isEqualTo("user:" + USER_ID + ":notes");
    }

    @Test
    void rememberMany_ShouldStoreItemsAcrossMultipleShelves() {
        var response = tool.rememberMany(List.of(
                new MemoryItem("movies", "Uncut Gems (2019) — Adam Sandler as a charming catastrophe."),
                new MemoryItem("movies", "Strange Days (1995) — sci-fi noir with recorded experiences."),
                new MemoryItem("series", "Mare of Easttown (2021, HBO)"),
                new MemoryItem("series", "Ripley (2024, Netflix)")
        ));

        assertThat(pageStore.size()).isEqualTo(4);
        assertThat(response).contains("Stored 4 memories");
        assertThat(response).contains("movies (2)");
        assertThat(response).contains("series (2)");

        var movies = pageStore.search("Uncut Gems", 1).getFirst();
        assertThat(movies.page().book().shelf().name()).isEqualTo("user:" + USER_ID + ":movies");
        var series = pageStore.search("Mare of Easttown", 1).getFirst();
        assertThat(series.page().book().shelf().name()).isEqualTo("user:" + USER_ID + ":series");
    }

    @Test
    void rememberMany_ShouldAllocateDistinctChunkIndicesPerShelf() {
        tool.rememberMany(List.of(
                new MemoryItem("movies", "Civil War (2024)"),
                new MemoryItem("movies", "Uncut Gems (2019)"),
                new MemoryItem("movies", "Strange Days (1995)")
        ));

        var pages = pageStore.recent(10);
        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(p -> p.chunkIndex()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(pages).extracting(p -> p.id()).doesNotHaveDuplicates();
    }

    @Test
    void rememberMany_ShouldContinueChunkSequenceFromExistingRemember() {
        tool.remember("Civil War (2024)", "movies");
        tool.rememberMany(List.of(
                new MemoryItem("movies", "Uncut Gems (2019)"),
                new MemoryItem("movies", "Strange Days (1995)")
        ));

        var pages = pageStore.recent(10);
        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(p -> p.chunkIndex()).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void rememberMany_ShouldSkipBlankContentButStoreRest() {
        var response = tool.rememberMany(List.of(
                new MemoryItem("movies", "Civil War (2024)"),
                new MemoryItem("movies", "   "),
                new MemoryItem("movies", null)
        ));

        assertThat(pageStore.size()).isEqualTo(1);
        assertThat(response).contains("Stored 1 memory");
        assertThat(response).contains("Skipped 2 blank item(s)");
    }

    @Test
    void rememberMany_WhenEmptyList_ShouldBeNoOp() {
        var response = tool.rememberMany(List.of());

        assertThat(pageStore.size()).isZero();
        assertThat(response).containsIgnoringCase("nothing to remember");
    }

    @Test
    void rememberMany_WhenAllBlank_ShouldBeNoOp() {
        var response = tool.rememberMany(List.of(
                new MemoryItem("notes", ""),
                new MemoryItem("notes", "   ")
        ));

        assertThat(pageStore.size()).isZero();
        assertThat(response).containsIgnoringCase("nothing to remember");
    }

    @Test
    void rememberMany_ShouldNormaliseShelfLabels() {
        tool.rememberMany(List.of(
                new MemoryItem("TV Series", "Mare of Easttown (2021, HBO)"),
                new MemoryItem("", "Orphan note without a shelf.")
        ));

        var pages = pageStore.recent(10);
        assertThat(pages).extracting(p -> p.book().shelf().name())
                .containsExactlyInAnyOrder(
                        "user:" + USER_ID + ":tv-series",
                        "user:" + USER_ID + ":notes"
                );
    }

    @Test
    void findForgetCandidates_ShouldListUserPagesWithIds() {
        tool.remember("I prefer a slightly louder volume from now on.", "preferences");

        var response = tool.findForgetCandidates("louder volume");
        var pageId = pageStore.recent(1).getFirst().id();

        assertThat(response).contains("Candidates for");
        assertThat(response).contains("pageId=" + pageId);
        assertThat(response).contains("shelf=preferences");
        assertThat(pageStore.size()).isEqualTo(1);
    }

    @Test
    void findForgetCandidates_WhenNoMatch_ShouldReturnFriendlyMessage() {
        var response = tool.findForgetCandidates("something nobody ever said");

        assertThat(response).containsIgnoringCase("no candidates");
    }

    @Test
    void findForgetCandidates_WhenBlankPhrase_ShouldReturnFriendlyMessage() {
        var response = tool.findForgetCandidates("   ");

        assertThat(response).containsIgnoringCase("no candidates");
    }

    @Test
    void findForgetCandidates_ShouldHideOtherUsersAndGlobalPages() {
        tool.remember("User 42 private note about volume preference.", "preferences");

        UserContext.set("other-user");
        tool.remember("Other user note about volume preference.", "preferences");

        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/volume.md"),
                "volume.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var docsPageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(docsPageId,
                        "Public doc mentioning volume preference levels.", 0, docsBook)
        ));

        UserContext.set(USER_ID);
        var response = tool.findForgetCandidates("volume preference");

        assertThat(response).contains("User 42");
        assertThat(response).doesNotContain("Other user");
        assertThat(response).doesNotContain("Public doc");
    }

    @Test
    void forgetById_ShouldDeleteTargetedPage() {
        tool.remember("I prefer a slightly louder volume from now on.", "preferences");
        var pageId = pageStore.recent(1).getFirst().id();

        var response = tool.forgetById(pageId);

        assertThat(pageStore.size()).isZero();
        assertThat(response).containsIgnoringCase("forgot");
    }

    @Test
    void forgetById_WhenPageMissing_ShouldReturnFriendlyMessage() {
        var response = tool.forgetById("page-that-does-not-exist");

        assertThat(response).containsIgnoringCase("nothing to forget");
    }

    @Test
    void forgetById_ShouldRefuseOtherUsersPage() {
        UserContext.set("other-user");
        tool.remember("Other user private note.", "preferences");
        var otherPageId = pageStore.recent(1).getFirst().id();

        UserContext.set(USER_ID);
        var response = tool.forgetById(otherPageId);

        assertThat(pageStore.size()).isEqualTo(1);
        assertThat(response).containsIgnoringCase("refused");
    }

    @Test
    void forgetById_ShouldRefuseGlobalDocsPage() {
        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/deployment.md"),
                "deployment.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var docsPageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(docsPageId,
                        "Deployment pipelines for the production environment.", 0, docsBook)
        ));

        var response = tool.forgetById(docsPageId);

        assertThat(pageStore.size()).isEqualTo(1);
        assertThat(response).containsIgnoringCase("refused");
    }

    @Test
    void listShelves_WhenEmpty_ShouldSayMemoryEmpty() {
        var response = tool.listShelves();

        assertThat(response).containsIgnoringCase("memory is empty");
    }

    @Test
    void listShelves_ShouldShowCurrentUsersShelvesWithoutPrefix() {
        tool.remember("I prefer a slightly louder volume.", "preferences");
        tool.remember("I use HotChocolate for GraphQL.", "tech");
        tool.remember("Second tech note about MSK.", "tech");

        var response = tool.listShelves();

        assertThat(response).contains("Your shelves:");
        assertThat(response).contains("- tech (2)");
        assertThat(response).contains("- preferences (1)");
        assertThat(response).doesNotContain("user:" + USER_ID);
    }

    @Test
    void listShelves_ShouldIncludeGlobalShelves() {
        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/deployment.md"),
                "deployment.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var pageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(pageId, "Deployment pipelines.", 0, docsBook)
        ));
        tool.remember("Personal note.", "notes");

        var response = tool.listShelves();

        assertThat(response).contains("Global shelves:");
        assertThat(response).contains("- docs (1)");
        assertThat(response).contains("Your shelves:");
        assertThat(response).contains("- notes (1)");
    }

    @Test
    void listShelves_ShouldHideOtherUsersShelves() {
        tool.remember("User 42 note.", "preferences");

        UserContext.set("other-user");
        tool.remember("Other user note 1.", "preferences");
        tool.remember("Other user note 2.", "secrets");

        UserContext.set(USER_ID);
        var response = tool.listShelves();

        assertThat(response).contains("- preferences (1)");
        assertThat(response).doesNotContain("secrets");
        assertThat(response).doesNotContain("user:other-user");
    }

    @Test
    void searchMemory_ShouldExcludeOtherUsersPages() {
        tool.remember("User 42 prefers dark roast coffee in the morning.", "preferences");

        UserContext.set("other-user");
        tool.remember("Other user also prefers dark roast coffee in the morning.", "preferences");

        UserContext.set(USER_ID);
        var response = tool.searchMemory("dark roast coffee");

        assertThat(response).contains("User 42");
        assertThat(response).doesNotContain("Other user");
        assertThat(response).doesNotContain("user:other-user");
    }

    @Test
    void searchMemory_ShouldIncludeGlobalShelves() {
        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/coffee.md"),
                "coffee.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var pageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(pageId,
                        "Mined documentation about dark roast coffee brewing techniques.", 0, docsBook)
        ));

        var response = tool.searchMemory("dark roast coffee");

        assertThat(response).contains("Mined documentation");
    }

    @Test
    void searchMemory_ShouldUseDisplayShelfNames() {
        tool.remember("User 42 prefers dark roast coffee.", "preferences");

        var response = tool.searchMemory("dark roast");

        assertThat(response).contains("preferences");
        assertThat(response).doesNotContain("user:" + USER_ID);
    }

    @Test
    void recall_ShouldResolveUserShelfWithoutPrefix() {
        tool.remember("Likes Railroad Red.", "wines");
        tool.remember("Likes Eikendal Charisma 2021.", "wines");

        var response = tool.recall("wines", null);

        assertThat(response).contains("Railroad Red");
        assertThat(response).contains("Eikendal Charisma");
        assertThat(response).doesNotContain("user:" + USER_ID);
    }

    @Test
    void recall_ShouldFallBackToGlobalShelf() {
        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/deployment.md"),
                "deployment.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var pageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(pageId,
                        "Deployment pipelines for the production environment.", 0, docsBook)
        ));

        var response = tool.recall("docs", null);

        assertThat(response).contains("Deployment pipelines");
    }

    @Test
    void recall_WithQuery_ShouldNarrowWithinShelf() {
        tool.remember("Likes Railroad Red.", "wines");
        tool.remember("Likes Eikendal Charisma 2021 Chenin Blanc.", "wines");

        var response = tool.recall("wines", "Eikendal");

        assertThat(response).contains("Eikendal");
    }

    @Test
    void recall_WhenShelfUnknown_ShouldReturnFriendlyMessage() {
        var response = tool.recall("nonexistent", null);

        assertThat(response).containsIgnoringCase("no memories on shelf");
    }

    @Test
    void recall_WhenBlankShelf_ShouldReturnFriendlyMessage() {
        var response = tool.recall("  ", null);

        assertThat(response).containsIgnoringCase("no shelf");
    }

    @Test
    void moveToShelf_ShouldMoveAllPagesAtomically() {
        tool.remember("Likes Railroad Red.", "notes");
        tool.remember("Likes Warwick First Lady Cabernet Sauvignon.", "notes");
        tool.remember("Likes Eikendal Charisma 2021.", "notes");
        assertThat(pageStore.size()).isEqualTo(3);

        var response = tool.moveToShelf("notes", "wines");

        assertThat(response).contains("Moved 3 pages");
        assertThat(pageStore.size()).isEqualTo(3);

        var notesShelfStorage = "user:" + USER_ID + ":notes";
        var winesShelfStorage = "user:" + USER_ID + ":wines";
        assertThat(pageStore.recentByShelf(notesShelfStorage, 10)).isEmpty();
        assertThat(pageStore.recentByShelf(winesShelfStorage, 10)).hasSize(3);
    }

    @Test
    void moveToShelf_ShouldRefuseGlobalSourceShelf() {
        var docsBook = new brainjar.recall.model.Book(
                java.nio.file.Path.of("/docs/deployment.md"),
                "deployment.md",
                new brainjar.recall.model.Shelf("docs"),
                java.time.Instant.now()
        );
        var pageId = brainjar.recall.model.Page.generateId(docsBook.sourcePath().toString(), 0);
        pageStore.store(java.util.List.of(
                new brainjar.recall.model.Page(pageId, "Mined doc.", 0, docsBook)
        ));
        tool.remember("Personal note.", "wines");

        var response = tool.moveToShelf("docs", "wines");

        assertThat(response).containsIgnoringCase("nothing to move");
        assertThat(pageStore.recentByShelf("docs", 10)).hasSize(1);
    }

    @Test
    void moveToShelf_WhenSourceEmpty_ShouldReturnFriendlyMessage() {
        var response = tool.moveToShelf("wines", "preferences");

        assertThat(response).containsIgnoringCase("nothing to move");
        assertThat(pageStore.size()).isZero();
    }

    @Test
    void moveToShelf_WhenSameShelf_ShouldRefuse() {
        tool.remember("Likes Railroad Red.", "wines");

        var response = tool.moveToShelf("wines", "wines");

        assertThat(response).containsIgnoringCase("refused");
        assertThat(pageStore.size()).isEqualTo(1);
    }

    @Test
    void moveToShelf_WhenBlankArgs_ShouldRefuse() {
        var blankFrom = tool.moveToShelf("", "wines");
        var blankTo = tool.moveToShelf("wines", "");

        assertThat(blankFrom).containsIgnoringCase("refused");
        assertThat(blankTo).containsIgnoringCase("refused");
    }

    @Test
    void moveToShelf_ShouldNotTouchOtherUsersPages() {
        UserContext.set("other-user");
        tool.remember("Other user note.", "notes");

        UserContext.set(USER_ID);
        tool.remember("My note.", "notes");

        var response = tool.moveToShelf("notes", "archive");

        assertThat(response).contains("Moved 1 page");
        // other-user's notes shelf is untouched
        var otherStorage = "user:other-user:notes";
        assertThat(pageStore.recentByShelf(otherStorage, 10)).hasSize(1);
    }

    @Test
    void forgetById_ShouldCloseKnowledgeGraphTriplesFromDeletedPage() {
        tool.remember("I use HotChocolate for GraphQL in C#.", "tech");
        var pageId = pageStore.recent(1).getFirst().id();
        knowledgeGraph.addTriple("user", "uses", "HotChocolate",
                LocalDate.now().minusDays(1), 1.0, pageId);
        assertThat(knowledgeGraph.query("user")).hasSize(1);

        tool.forgetById(pageId);

        var facts = knowledgeGraph.queryAsOf("user", LocalDate.now().plusDays(1));
        assertThat(facts).isEmpty();
    }
}
