package brainjar.schedule;

import java.util.List;
import java.util.Optional;

public interface JobStore {

    void save(ScheduledJob job);

    Optional<ScheduledJob> findById(String jobId);

    List<ScheduledJob> findByUser(String userId);

    List<ScheduledJob> all();

    /** Removes the job with the given id. No-op if missing. */
    void delete(String jobId);
}
