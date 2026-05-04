package brainjar.recall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserShelvesTest {

    private static final String USER = "user-42";

    @Test
    void toStorage_ShouldPrefixWithUserId() {
        assertThat(UserShelves.toStorage(USER, "wines"))
                .isEqualTo("user:user-42:wines");
    }

    @Test
    void toStorage_ShouldNormaliseDisplayName() {
        assertThat(UserShelves.toStorage(USER, "TV Series"))
                .isEqualTo("user:user-42:tv-series");
    }

    @Test
    void toStorage_WhenBlankShelf_ShouldDefaultToNotes() {
        assertThat(UserShelves.toStorage(USER, "  "))
                .isEqualTo("user:user-42:notes");
    }

    @Test
    void toStorage_WhenBlankUserId_ShouldThrow() {
        assertThatThrownBy(() -> UserShelves.toStorage("", "wines"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserShelves.toStorage(null, "wines"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toDisplay_WhenOwnShelf_ShouldStripPrefix() {
        assertThat(UserShelves.toDisplay(USER, "user:user-42:wines"))
                .isEqualTo("wines");
    }

    @Test
    void toDisplay_WhenGlobalShelf_ShouldPassThrough() {
        assertThat(UserShelves.toDisplay(USER, "docs"))
                .isEqualTo("docs");
    }

    @Test
    void toDisplay_WhenAnotherUsersShelf_ShouldReturnRaw() {
        assertThat(UserShelves.toDisplay(USER, "user:other-user:secrets"))
                .isEqualTo("user:other-user:secrets");
    }

    @Test
    void toDisplay_WhenNullUserId_ShouldReturnRawForUserScoped() {
        assertThat(UserShelves.toDisplay(null, "user:somebody:wines"))
                .isEqualTo("user:somebody:wines");
    }

    @Test
    void isOwnedBy_ShouldOnlyMatchOwnPrefix() {
        assertThat(UserShelves.isOwnedBy(USER, "user:user-42:wines")).isTrue();
        assertThat(UserShelves.isOwnedBy(USER, "user:other-user:wines")).isFalse();
        assertThat(UserShelves.isOwnedBy(USER, "docs")).isFalse();
        assertThat(UserShelves.isOwnedBy(USER, null)).isFalse();
        assertThat(UserShelves.isOwnedBy(null, "user:user-42:wines")).isFalse();
    }

    @Test
    void isUserScoped_ShouldDetectUserPrefix() {
        assertThat(UserShelves.isUserScoped("user:user-42:wines")).isTrue();
        assertThat(UserShelves.isUserScoped("user:other:secrets")).isTrue();
        assertThat(UserShelves.isUserScoped("docs")).isFalse();
        assertThat(UserShelves.isUserScoped(null)).isFalse();
    }

    @Test
    void isVisibleTo_ShouldAllowOwnAndGlobal() {
        assertThat(UserShelves.isVisibleTo(USER, "user:user-42:wines")).isTrue();
        assertThat(UserShelves.isVisibleTo(USER, "docs")).isTrue();
        assertThat(UserShelves.isVisibleTo(USER, "user:other-user:wines")).isFalse();
    }

    @Test
    void normalise_ShouldLowercaseAndReplaceJunk() {
        assertThat(UserShelves.normalise("Wines")).isEqualTo("wines");
        assertThat(UserShelves.normalise("TV Series")).isEqualTo("tv-series");
        assertThat(UserShelves.normalise("Movies & Shows!")).isEqualTo("movies-shows");
        assertThat(UserShelves.normalise("   ")).isEqualTo("notes");
        assertThat(UserShelves.normalise(null)).isEqualTo("notes");
        assertThat(UserShelves.normalise("???")).isEqualTo("notes");
    }

    @Test
    void normalise_ShouldCollapseAndTrimDashes() {
        assertThat(UserShelves.normalise("--wines--")).isEqualTo("wines");
        assertThat(UserShelves.normalise("tv  series")).isEqualTo("tv-series");
    }
}
