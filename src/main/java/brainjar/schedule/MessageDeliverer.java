package brainjar.schedule;

/**
 * Delivers a text message to a user out-of-band (not in response to an incoming
 * message). The primary implementation is Discord DM; abstracted so the
 * scheduler package can be unit-tested without JDA.
 */
public interface MessageDeliverer {

    void deliver(String userId, String message);
}
