package brainjar.schedule;

import brainjar.context.UserContext;
import brainjar.discord.ai.BrainJarAssistant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Fires a scheduled job: re-prompts Perry with the stored prompt on behalf of
 * the job's user, then DMs the reply back. One-shot jobs are removed from the
 * store after a successful fire; recurring jobs stay.
 *
 * <p>Triggered via {@link JobFiredEvent} / {@link JobMissedEvent} from
 * {@link JobScheduler}. The events-based handoff exists to keep the wiring
 * graph acyclic (this class depends on {@code BrainJarAssistant}, which
 * transitively depends on {@code JobScheduler}).
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final String PROMPT_PREFIX = "[scheduled reminder fired — respond to the user as if this is the reminder going off] ";
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final int PROMPT_PREVIEW_LEN = 120;

    private final BrainJarAssistant assistant;
    private final MessageDeliverer deliverer;
    private final JobStore jobStore;
    private final ScheduleProperties properties;

    public JobRunner(BrainJarAssistant assistant,
                     MessageDeliverer deliverer,
                     JobStore jobStore,
                     ScheduleProperties properties) {
        this.assistant = assistant;
        this.deliverer = deliverer;
        this.jobStore = jobStore;
        this.properties = properties;
    }

    @EventListener
    void onFire(JobFiredEvent event) {
        fire(event.job());
    }

    @EventListener
    void onMissed(JobMissedEvent event) {
        notifyMissed(event.job());
    }

    public void fire(ScheduledJob job) {
        log.info("Firing job id={} user={} kind={} prompt=\"{}\"",
                job.id(), job.userId(), job.kind(), job.prompt());

        UserContext.set(job.userId());
        try {
            var reply = assistant.chat(job.userId(), PROMPT_PREFIX + job.prompt());
            deliverer.deliver(job.userId(), reply);
        } catch (RuntimeException e) {
            log.error("Job id={} fire failed", job.id(), e);
        } finally {
            UserContext.clear();
        }

        if (job.kind() == JobKind.ONCE) {
            jobStore.delete(job.id());
            log.info("One-shot job id={} removed after fire", job.id());
        }
    }

    /**
     * Notify the user that a one-shot job was missed (e.g. because the app
     * was offline when it was due). Sends a direct DM — the assistant is NOT
     * re-prompted, so no LLM cost and no risk of a stale-context reply. The
     * job is then removed from the store.
     *
     * Only meaningful for {@link JobKind#ONCE}; cron misses are not notified
     * because a recurring job simply picks up its next scheduled fire.
     */
    public void notifyMissed(ScheduledJob job) {
        if (job.kind() != JobKind.ONCE) {
            log.debug("notifyMissed ignored for non-ONCE job id={} kind={}", job.id(), job.kind());
            return;
        }
        var message = buildMissedMessage(job);
        log.warn("Job id={} user={} missed (fireAt={}) — notifying user, not firing",
                job.id(), job.userId(), job.fireAt());

        try {
            deliverer.deliver(job.userId(), message);
        } catch (RuntimeException e) {
            log.error("Failed to deliver missed-job notice for id={}", job.id(), e);
        }

        jobStore.delete(job.id());
    }

    private String buildMissedMessage(ScheduledJob job) {
        var when = DISPLAY_FORMAT.format(job.fireAt().atZone(properties.zoneId()));
        var sb = new StringBuilder("Heads up — I missed a scheduled reminder that was due at ")
                .append(when)
                .append(". I was offline and didn't fire it.");
        if (job.note() != null && !job.note().isBlank()) {
            sb.append("\nNote: ").append(job.note()).append(".");
        }
        sb.append("\nPrompt was: \"").append(truncate(job.prompt(), PROMPT_PREVIEW_LEN)).append("\".")
                .append("\nLet me know if you want to reschedule.");
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        var single = text.replaceAll("\\s+", " ").strip();
        if (single.length() <= max) return single;
        return single.substring(0, max - 3) + "...";
    }
}
