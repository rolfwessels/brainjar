package brainjar.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileJobStoreTest {

    @Test
    void save_AndReload_ShouldPreserveOnceJob(@TempDir Path tmp) {
        var file = tmp.resolve("schedules.json");
        var store = new FileJobStore(file);
        var job = ScheduledJob.once("job_abc", "user-1", "ping me", "test",
                Instant.parse("2027-01-01T10:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"));

        store.save(job);

        var reloaded = new FileJobStore(file);
        var found = reloaded.findById("job_abc").orElseThrow();
        assertThat(found.userId()).isEqualTo("user-1");
        assertThat(found.kind()).isEqualTo(JobKind.ONCE);
        assertThat(found.prompt()).isEqualTo("ping me");
        assertThat(found.note()).isEqualTo("test");
        assertThat(found.fireAt()).isEqualTo(Instant.parse("2027-01-01T10:00:00Z"));
        assertThat(found.cron()).isNull();
    }

    @Test
    void save_AndReload_ShouldPreserveCronJob(@TempDir Path tmp) {
        var file = tmp.resolve("schedules.json");
        var store = new FileJobStore(file);
        var job = ScheduledJob.recurring("job_rec", "user-1", "daily check", null,
                "0 0 9 * * *", Instant.now());

        store.save(job);

        var reloaded = new FileJobStore(file);
        var found = reloaded.findById("job_rec").orElseThrow();
        assertThat(found.kind()).isEqualTo(JobKind.CRON);
        assertThat(found.cron()).isEqualTo("0 0 9 * * *");
        assertThat(found.fireAt()).isNull();
    }

    @Test
    void findByUser_ShouldScopeResults(@TempDir Path tmp) {
        var store = new FileJobStore(tmp.resolve("schedules.json"));
        store.save(ScheduledJob.once("job_a", "user-1", "a", null,
                Instant.parse("2027-01-01T10:00:00Z"), Instant.now()));
        store.save(ScheduledJob.once("job_b", "user-2", "b", null,
                Instant.parse("2027-01-01T11:00:00Z"), Instant.now()));

        assertThat(store.findByUser("user-1"))
                .extracting(ScheduledJob::id)
                .containsExactly("job_a");
    }

    @Test
    void delete_ShouldRemoveAndPersist(@TempDir Path tmp) {
        var file = tmp.resolve("schedules.json");
        var store = new FileJobStore(file);
        var job = ScheduledJob.once("job_x", "user-1", "x", null,
                Instant.parse("2027-01-01T10:00:00Z"), Instant.now());
        store.save(job);

        store.delete("job_x");

        assertThat(new FileJobStore(file).findById("job_x")).isEmpty();
    }

    @Test
    void delete_WhenMissing_ShouldBeNoOp(@TempDir Path tmp) {
        var store = new FileJobStore(tmp.resolve("schedules.json"));

        store.delete("does-not-exist");

        assertThat(store.all()).isEmpty();
    }

    @Test
    void newStore_WhenFileMissing_ShouldStartEmpty(@TempDir Path tmp) {
        var store = new FileJobStore(tmp.resolve("schedules.json"));

        assertThat(store.all()).isEmpty();
    }
}
