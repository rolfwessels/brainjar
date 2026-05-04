package brainjar.recall.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteChatHistoryStoreTest {

    private SqliteChatHistoryStore store;

    private void setup() {
        store = new SqliteChatHistoryStore("jdbc:sqlite::memory:");
    }

    @Test
    void getMessagesJson_WhenNoHistory_ShouldReturnNull() {
        setup();

        assertThat(store.getMessagesJson("user-1")).isNull();
    }

    @Test
    void updateMessagesJson_ShouldPersistAndBeRetrievable() {
        setup();

        store.updateMessagesJson("user-1", "[{\"type\":\"USER\",\"text\":\"hello\"}]");

        assertThat(store.getMessagesJson("user-1"))
                .isEqualTo("[{\"type\":\"USER\",\"text\":\"hello\"}]");
    }

    @Test
    void updateMessagesJson_WhenCalledTwice_ShouldOverwritePrevious() {
        setup();
        store.updateMessagesJson("user-1", "first");

        store.updateMessagesJson("user-1", "second");

        assertThat(store.getMessagesJson("user-1")).isEqualTo("second");
    }

    @Test
    void deleteMessages_ShouldRemoveHistory() {
        setup();
        store.updateMessagesJson("user-1", "some messages");

        store.deleteMessages("user-1");

        assertThat(store.getMessagesJson("user-1")).isNull();
    }

    @Test
    void deleteMessages_WhenNoHistory_ShouldNotThrow() {
        setup();

        store.deleteMessages("ghost-user");
    }

    @Test
    void messages_ShouldBeIsolatedPerUser() {
        setup();
        store.updateMessagesJson("user-1", "alice messages");
        store.updateMessagesJson("user-2", "bob messages");

        assertThat(store.getMessagesJson("user-1")).isEqualTo("alice messages");
        assertThat(store.getMessagesJson("user-2")).isEqualTo("bob messages");
    }
}
