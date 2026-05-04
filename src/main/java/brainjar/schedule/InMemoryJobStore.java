package brainjar.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryJobStore implements JobStore {

    private final ConcurrentMap<String, ScheduledJob> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(ScheduledJob job) {
        jobs.put(job.id(), job);
    }

    @Override
    public Optional<ScheduledJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<ScheduledJob> findByUser(String userId) {
        return jobs.values().stream()
                .filter(j -> j.userId().equals(userId))
                .toList();
    }

    @Override
    public List<ScheduledJob> all() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public void delete(String jobId) {
        jobs.remove(jobId);
    }
}
