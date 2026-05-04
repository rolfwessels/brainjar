package brainjar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Registers scheduled jobs with Spring's {@link TaskScheduler} and keeps a
 * handle so they can be cancelled. Stateful only in memory — on restart, the
 * {@code ScheduleConfig} re-registers everything from the persisted store.
 *
 * <p>The actual job execution (re-prompting Perry, delivering the reply) is
 * owned by {@link JobRunner}. We dispatch to it via {@link JobFiredEvent} /
 * {@link JobMissedEvent} rather than a direct dependency so the wiring graph
 * stays acyclic (see package javadoc / docs for the cycle shape).
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ApplicationEventPublisher events;
    private final JobStore jobStore;
    private final ScheduleProperties properties;
    private final ConcurrentMap<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public JobScheduler(@Qualifier(ScheduleConfig.SCHEDULER_BEAN) TaskScheduler taskScheduler,
                        ApplicationEventPublisher events,
                        JobStore jobStore,
                        ScheduleProperties properties) {
        this.taskScheduler = taskScheduler;
        this.events = events;
        this.jobStore = jobStore;
        this.properties = properties;
    }

    /**
     * Register (or re-register) a job with the underlying scheduler. Any
     * existing registration for the same id is cancelled first.
     */
    public void register(ScheduledJob job) {
        cancelFuture(job.id());
        switch (job.kind()) {
            case ONCE -> registerOnce(job);
            case CRON -> registerCron(job);
        }
    }

    /** Cancels the scheduled future and removes it from the registry. */
    public void cancel(String jobId) {
        cancelFuture(jobId);
    }

    private void registerOnce(ScheduledJob job) {
        if (job.fireAt().isBefore(Instant.now())) {
            events.publishEvent(new JobMissedEvent(job));
            return;
        }
        var future = taskScheduler.schedule(() -> events.publishEvent(new JobFiredEvent(job)), job.fireAt());
        if (future != null) {
            futures.put(job.id(), future);
        }
        log.info("Registered one-shot job id={} user={} fireAt={}", job.id(), job.userId(), job.fireAt());
    }

    private void registerCron(ScheduledJob job) {
        var expr = job.cron();
        if (!CronExpression.isValidExpression(expr)) {
            log.warn("Dropping cron job id={} — invalid expression: {}", job.id(), expr);
            jobStore.delete(job.id());
            return;
        }
        var future = taskScheduler.schedule(() -> events.publishEvent(new JobFiredEvent(job)),
                new CronTrigger(expr, TimeZone.getTimeZone(properties.zoneId())));
        if (future != null) {
            futures.put(job.id(), future);
        }
        log.info("Registered cron job id={} user={} cron=\"{}\"", job.id(), job.userId(), job.cron());
    }

    private void cancelFuture(String jobId) {
        var existing = futures.remove(jobId);
        if (existing != null) {
            existing.cancel(false);
        }
    }
}
