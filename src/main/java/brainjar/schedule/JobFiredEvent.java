package brainjar.schedule;

/**
 * Published by {@link JobScheduler} when a scheduled job's trigger fires.
 * Decouples the scheduler from the {@link JobRunner} that actually re-prompts
 * Perry and delivers the reply, breaking what would otherwise be a wiring
 * cycle through {@code BrainJarAssistant} / {@code MessageDeliverer}.
 */
public record JobFiredEvent(ScheduledJob job) {
}
