package brainjar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application startup (after JDA is ready), re-registers every persisted
 * scheduled job with the running scheduler. One-shots whose fireAt is in the
 * past become missed-notifications via {@link JobScheduler#register}.
 */
@Component
public class JobRestorer {

    private static final Logger log = LoggerFactory.getLogger(JobRestorer.class);

    private final JobStore jobStore;
    private final JobScheduler jobScheduler;

    public JobRestorer(JobStore jobStore, JobScheduler jobScheduler) {
        this.jobStore = jobStore;
        this.jobScheduler = jobScheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreJobsOnStartup() {
        var jobs = jobStore.all();
        if (jobs.isEmpty()) {
            log.info("No persisted scheduled jobs to restore.");
            return;
        }
        log.info("Restoring {} persisted scheduled job(s)", jobs.size());
        for (var job : jobs) {
            try {
                jobScheduler.register(job);
            } catch (RuntimeException e) {
                log.error("Failed to restore job id={}", job.id(), e);
            }
        }
    }
}
