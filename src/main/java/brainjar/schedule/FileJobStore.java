package brainjar.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JSON-backed job store. Reads the file once on construction, rewrites it on
 * every mutation. Expected volumes are tiny (dozens of jobs at most), so a
 * full-file rewrite is fine.
 */
public class FileJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(FileJobStore.class);

    private final Path path;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, ScheduledJob> jobs = new ConcurrentHashMap<>();

    public FileJobStore(Path path) {
        this.path = path;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        loadIfExists();
    }

    @Override
    public void save(ScheduledJob job) {
        jobs.put(job.id(), job);
        persist();
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
        if (jobs.remove(jobId) != null) {
            persist();
        }
    }

    private void loadIfExists() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            var loaded = mapper.readValue(Files.readAllBytes(path),
                    new TypeReference<List<ScheduledJob>>() {});
            loaded.forEach(j -> jobs.put(j.id(), j));
            log.info("Loaded {} scheduled job(s) from {}", loaded.size(), path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load scheduled jobs from " + path, e);
        }
    }

    private void persist() {
        try {
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(jobs.values());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist scheduled jobs to " + path, e);
        }
    }
}
