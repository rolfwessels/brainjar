package brainjar.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void get_WhenUnset_ShouldBeEmpty() {
        assertThat(UserContext.get()).isEmpty();
    }

    @Test
    void set_ShouldStoreUserId() {
        UserContext.set("user-123");

        assertThat(UserContext.get()).contains("user-123");
    }

    @Test
    void getOrAnonymous_WhenUnset_ShouldReturnAnonymous() {
        assertThat(UserContext.getOrAnonymous()).isEqualTo("anonymous");
    }

    @Test
    void getOrAnonymous_WhenSet_ShouldReturnUserId() {
        UserContext.set("user-123");

        assertThat(UserContext.getOrAnonymous()).isEqualTo("user-123");
    }

    @Test
    void clear_ShouldRemoveUserId() {
        UserContext.set("user-123");

        UserContext.clear();

        assertThat(UserContext.get()).isEmpty();
    }

    @Test
    void set_ShouldBeThreadLocal() throws InterruptedException {
        UserContext.set("main-user");
        var other = new String[1];

        var thread = new Thread(() -> other[0] = UserContext.getOrAnonymous());
        thread.start();
        thread.join();

        assertThat(other[0]).isEqualTo("anonymous");
        assertThat(UserContext.getOrAnonymous()).isEqualTo("main-user");
    }
}
