package brainjar.schedule;

import java.time.Instant;

/**
 * A scheduled job — either a one-shot at {@link #fireAt()} or a recurring
 * {@code CRON} job driven by {@link #cron()}.
 *
 * Jobs are scoped to a single user; the scheduler will DM that user when the
 * job fires.
 */
public record ScheduledJob(
        String id,
        String userId,
        JobKind kind,
        String prompt,
        String note,
        Instant fireAt,
        String cron,
        Instant createdAt) {

    public static ScheduledJob once(String id, String userId, String prompt, String note,
                                    Instant fireAt, Instant createdAt) {
        return new ScheduledJob(id, userId, JobKind.ONCE, prompt, note, fireAt, null, createdAt);
    }

    public static ScheduledJob recurring(String id, String userId, String prompt, String note,
                                         String cron, Instant createdAt) {
        return new ScheduledJob(id, userId, JobKind.CRON, prompt, note, null, cron, createdAt);
    }
}
