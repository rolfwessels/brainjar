package brainjar.schedule;

import brainjar.context.UserContext;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.UUID;

/**
 * Tools exposed to Perry for scheduling reminders/tasks that fire at a future
 * time and are DM'd back to the user. Two-step flow for deletion mirrors the
 * forget tools: {@link #listScheduledJobs()} surfaces ids, then
 * {@link #cancelScheduledJob(String)} removes by id.
 */
@Component
public class ScheduleTool {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTool.class);
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");
    private static final Duration MIN_LEAD_TIME = Duration.ofSeconds(5);

    private final JobStore jobStore;
    private final JobScheduler scheduler;
    private final ScheduleProperties properties;

    public ScheduleTool(JobStore jobStore, JobScheduler scheduler, ScheduleProperties properties) {
        this.jobStore = jobStore;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @Tool("Schedule a one-shot reminder/task. At the given local time, Perry is re-prompted with `prompt` and DMs the reply back to the user. "
            + "`when` must be an ISO local date-time string like '2026-04-20T09:00' or '2026-04-20T09:00:00'; it is interpreted in the app's configured timezone. "
            + "`note` is an optional short label shown in listings (e.g. 'call mom'). Returns the jobId.")
    public String scheduleOnce(String prompt, String when, String note) {
        var userId = UserContext.getOrAnonymous();
        if (prompt == null || prompt.isBlank()) {
            return "Cannot schedule — prompt was empty.";
        }
        if (when == null || when.isBlank()) {
            return "Cannot schedule — `when` was empty.";
        }

        Instant fireAt;
        try {
            fireAt = parseLocal(when);
        } catch (DateTimeParseException e) {
            return "Cannot schedule — couldn't parse `when` \"" + when + "\". "
                    + "Expected an ISO local date-time like 2026-04-20T09:00.";
        }

        var now = Instant.now();
        if (fireAt.isBefore(now.plus(MIN_LEAD_TIME))) {
            return "Cannot schedule — `when` must be at least " + MIN_LEAD_TIME.toSeconds()
                    + "s in the future. You gave " + formatInstant(fireAt) + ".";
        }

        var job = ScheduledJob.once(newId(), userId, prompt.strip(), nullIfBlank(note), fireAt, now);
        jobStore.save(job);
        scheduler.register(job);

        log.info("scheduleOnce user={} jobId={} fireAt={} prompt=\"{}\"",
                userId, job.id(), fireAt, prompt);
        return "Scheduled one-shot jobId=" + job.id() + " for " + formatInstant(fireAt) + ".";
    }

    @Tool("Schedule a recurring reminder/task. At each cron tick, Perry is re-prompted with `prompt` and DMs the reply back to the user. "
            + "`cron` is a Spring 6-field cron expression: 'second minute hour day-of-month month day-of-week'. Examples: "
            + "'0 0 9 * * MON-FRI' (weekdays 09:00), '0 30 7 * * *' (every day at 07:30), '0 0 * * * *' (top of every hour). "
            + "Cron ticks in the app's configured timezone. `note` is an optional short label. Returns the jobId.")
    public String scheduleRecurring(String prompt, String cron, String note) {
        var userId = UserContext.getOrAnonymous();
        if (prompt == null || prompt.isBlank()) {
            return "Cannot schedule — prompt was empty.";
        }
        if (cron == null || cron.isBlank()) {
            return "Cannot schedule — `cron` was empty.";
        }
        if (!CronExpression.isValidExpression(cron)) {
            return "Cannot schedule — invalid cron expression: \"" + cron + "\". "
                    + "Expected Spring 6-field format like '0 0 9 * * MON-FRI'.";
        }

        var job = ScheduledJob.recurring(newId(), userId, prompt.strip(), nullIfBlank(note),
                cron.strip(), Instant.now());
        jobStore.save(job);
        scheduler.register(job);

        log.info("scheduleRecurring user={} jobId={} cron=\"{}\" prompt=\"{}\"",
                userId, job.id(), cron, prompt);
        return "Scheduled recurring jobId=" + job.id() + " with cron \"" + cron + "\".";
    }

    @Tool("List the current user's scheduled jobs (one-shot and recurring) with their jobId, next-fire / cron, and note. "
            + "Deletes nothing. Use this before cancelScheduledJob to pick the right id.")
    public String listScheduledJobs() {
        var userId = UserContext.getOrAnonymous();
        var mine = jobStore.findByUser(userId).stream()
                .sorted(Comparator.comparing(ScheduledJob::createdAt))
                .toList();

        log.info("listScheduledJobs user={} count={}", userId, mine.size());

        if (mine.isEmpty()) {
            return "No scheduled jobs.";
        }
        var sb = new StringBuilder("Your scheduled jobs:\n");
        for (var job : mine) {
            sb.append("- jobId=").append(job.id());
            switch (job.kind()) {
                case ONCE -> sb.append(" ONCE at ").append(formatInstant(job.fireAt()));
                case CRON -> sb.append(" CRON \"").append(job.cron()).append("\"");
            }
            if (job.note() != null) {
                sb.append(" — ").append(job.note());
            }
            sb.append("\n  prompt: ").append(truncate(job.prompt(), 120)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    @Tool("Cancel a scheduled job by its jobId. Only cancels jobs belonging to the current user. Obtain the jobId via listScheduledJobs — do not guess.")
    public String cancelScheduledJob(String jobId) {
        var userId = UserContext.getOrAnonymous();
        if (jobId == null || jobId.isBlank()) {
            return "Cannot cancel — jobId was empty.";
        }
        var found = jobStore.findById(jobId);
        if (found.isEmpty()) {
            return "Cannot cancel — no job with id: " + jobId;
        }
        var job = found.get();
        if (!job.userId().equals(userId)) {
            log.warn("cancelScheduledJob user={} jobId={} owner={} refused", userId, jobId, job.userId());
            return "Refused — that job isn't yours.";
        }

        scheduler.cancel(jobId);
        jobStore.delete(jobId);
        log.info("cancelScheduledJob user={} jobId={} kind={}", userId, jobId, job.kind());
        return "Cancelled jobId=" + jobId + ".";
    }

    private Instant parseLocal(String raw) {
        var trimmed = raw.strip();
        var local = LocalDateTime.parse(trimmed);
        return ZonedDateTime.of(local, properties.zoneId()).toInstant();
    }

    private String formatInstant(Instant instant) {
        return DISPLAY_FORMAT.format(instant.atZone(properties.zoneId()));
    }

    private static String newId() {
        return "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static String truncate(String text, int max) {
        var single = text.replaceAll("\\s+", " ").strip();
        if (single.length() <= max) return single;
        return single.substring(0, max - 3) + "...";
    }
}
