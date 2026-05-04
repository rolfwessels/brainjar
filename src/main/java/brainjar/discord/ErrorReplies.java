package brainjar.discord;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Canned user-facing responses for when something went wrong on our side.
 * Perry stays silent if we don't send *something*, so we pick a random
 * line in his voice (dry, concise, self-aware) and move on.
 *
 * <p>The pool is small on purpose — the failure mode should be rare, and
 * if the user sees the same line twice in a row that's itself useful
 * feedback that something's wedged.
 */
public final class ErrorReplies {

    private static final List<String> REPLIES = List.of(
            "Something went sideways on my end. Give it another go?",
            "Brain fart. Mind trying that again?",
            "I fell over. Not my finest moment — try that once more?",
            "Well that didn't work. Another attempt and I promise to pay attention this time.",
            "My end broke in a way I didn't anticipate. Retry?",
            "I dropped that one. If you don't mind, send it again?",
            "Error on my side — not yours. Try that once more when you have a sec.",
            "That didn't make it through. Want to give it another shot?",
            "Something tripped internally. I'm still here if you want to retry.",
            "Whatever I was doing just fell off a cliff. Try again?"
    );

    private ErrorReplies() {
    }

    public static String pick() {
        return REPLIES.get(ThreadLocalRandom.current().nextInt(REPLIES.size()));
    }

    static int size() {
        return REPLIES.size();
    }
}
