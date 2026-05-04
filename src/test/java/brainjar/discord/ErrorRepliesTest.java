package brainjar.discord;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorRepliesTest {

    @Test
    void pick_ShouldAlwaysReturnNonEmptyString() {
        for (int i = 0; i < 50; i++) {
            var reply = ErrorReplies.pick();
            assertThat(reply).isNotBlank();
        }
    }

    @Test
    void pick_OverManyCalls_ShouldHitMultipleDistinctReplies() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            seen.add(ErrorReplies.pick());
        }

        assertThat(seen.size()).isGreaterThan(1);
    }

    @Test
    void pool_ShouldHaveAtLeastEightReplies() {
        assertThat(ErrorReplies.size()).isGreaterThanOrEqualTo(8);
    }
}
