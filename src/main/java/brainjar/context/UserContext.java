package brainjar.context;

import java.util.Optional;

/**
 * Per-request user identity, set by the transport layer (e.g. Discord listener)
 * and read by tools that need to scope their work to a user. Backed by a
 * ThreadLocal — safe for synchronous tool invocation chains that run on the
 * same thread as the originating message.
 */
public final class UserContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(String userId) {
        USER_ID.set(userId);
    }

    public static Optional<String> get() {
        return Optional.ofNullable(USER_ID.get());
    }

    public static String getOrAnonymous() {
        var userId = USER_ID.get();
        return userId != null ? userId : "anonymous";
    }

    public static void clear() {
        USER_ID.remove();
    }
}
