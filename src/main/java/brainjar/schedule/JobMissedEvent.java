package brainjar.schedule;

/**
 * Published by {@link JobScheduler} when a one-shot job is registered whose
 * {@code fireAt} is already in the past (e.g. the app was offline at the
 * scheduled time). {@link JobRunner} listens for this and sends a
 * missed-reminder DM without re-prompting Perry.
 */
public record JobMissedEvent(ScheduledJob job) {
}
