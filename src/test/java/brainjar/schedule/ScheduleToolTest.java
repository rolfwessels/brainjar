package brainjar.schedule;

import brainjar.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleToolTest {

    private static final String USER_ID = "user-42";
    private static final String ZONE = "Africa/Johannesburg";

    private InMemoryJobStore store;
    private RecordingScheduler scheduler;
    private ScheduleTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryJobStore();
        scheduler = new RecordingScheduler();
        var properties = new ScheduleProperties(ZONE, 2);
        tool = new ScheduleTool(store, scheduler.asJobScheduler(), properties);
        UserContext.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void scheduleOnce_ShouldStoreJobAndRegisterFuture() {
        var when = futureLocal(Duration.ofHours(1));

        var response = tool.scheduleOnce("ping me", when, "call mom");

        assertThat(response).containsIgnoringCase("scheduled one-shot");
        assertThat(store.all()).hasSize(1);
        var job = store.all().getFirst();
        assertThat(job.userId()).isEqualTo(USER_ID);
        assertThat(job.kind()).isEqualTo(JobKind.ONCE);
        assertThat(job.prompt()).isEqualTo("ping me");
        assertThat(job.note()).isEqualTo("call mom");
        assertThat(scheduler.registered).extracting(j -> j.id()).containsExactly(job.id());
    }

    @Test
    void scheduleOnce_WhenWhenInPast_ShouldReject() {
        var when = pastLocal(Duration.ofHours(1));

        var response = tool.scheduleOnce("ping me", when, null);

        assertThat(response).containsIgnoringCase("in the future");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void scheduleOnce_WhenInvalidFormat_ShouldReject() {
        var response = tool.scheduleOnce("ping me", "not-a-date", null);

        assertThat(response).containsIgnoringCase("couldn't parse");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void scheduleOnce_WhenBlankPrompt_ShouldReject() {
        var response = tool.scheduleOnce("   ", futureLocal(Duration.ofHours(1)), null);

        assertThat(response).containsIgnoringCase("prompt was empty");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void scheduleRecurring_ShouldStoreJobAndRegisterFuture() {
        var response = tool.scheduleRecurring("morning check", "0 0 9 * * MON-FRI", "work");

        assertThat(response).containsIgnoringCase("scheduled recurring");
        assertThat(store.all()).hasSize(1);
        var job = store.all().getFirst();
        assertThat(job.kind()).isEqualTo(JobKind.CRON);
        assertThat(job.cron()).isEqualTo("0 0 9 * * MON-FRI");
        assertThat(scheduler.registered).extracting(j -> j.id()).containsExactly(job.id());
    }

    @Test
    void scheduleRecurring_WhenInvalidCron_ShouldReject() {
        var response = tool.scheduleRecurring("morning check", "not a cron", null);

        assertThat(response).containsIgnoringCase("invalid cron");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void listScheduledJobs_WhenEmpty_ShouldSayNone() {
        assertThat(tool.listScheduledJobs()).containsIgnoringCase("no scheduled jobs");
    }

    @Test
    void listScheduledJobs_ShouldOnlyShowCurrentUsersJobs() {
        tool.scheduleOnce("my job", futureLocal(Duration.ofHours(1)), "mine");
        UserContext.set("other-user");
        tool.scheduleOnce("their job", futureLocal(Duration.ofHours(2)), "theirs");

        UserContext.set(USER_ID);
        var response = tool.listScheduledJobs();

        assertThat(response).contains("my job").contains("mine");
        assertThat(response).doesNotContain("their job").doesNotContain("theirs");
    }

    @Test
    void cancelScheduledJob_ShouldRemoveOwnedJob() {
        tool.scheduleOnce("ping me", futureLocal(Duration.ofHours(1)), null);
        var jobId = store.all().getFirst().id();

        var response = tool.cancelScheduledJob(jobId);

        assertThat(response).containsIgnoringCase("cancelled");
        assertThat(store.all()).isEmpty();
        assertThat(scheduler.cancelled).containsExactly(jobId);
    }

    @Test
    void cancelScheduledJob_ShouldRefuseOtherUsersJob() {
        UserContext.set("other-user");
        tool.scheduleOnce("their job", futureLocal(Duration.ofHours(1)), null);
        var otherJobId = store.all().getFirst().id();

        UserContext.set(USER_ID);
        var response = tool.cancelScheduledJob(otherJobId);

        assertThat(response).containsIgnoringCase("refused");
        assertThat(store.findById(otherJobId)).isPresent();
    }

    @Test
    void cancelScheduledJob_WhenUnknownId_ShouldReturnFriendlyMessage() {
        var response = tool.cancelScheduledJob("job_does_not_exist");

        assertThat(response).containsIgnoringCase("no job with id");
    }

    private static String futureLocal(Duration offset) {
        return ZonedDateTime.now(ZoneId.of(ZONE)).plus(offset)
                .toLocalDateTime().withNano(0).withSecond(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String pastLocal(Duration offset) {
        return LocalDateTime.now(ZoneId.of(ZONE)).minus(offset)
                .withNano(0).withSecond(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * A test double that captures register/cancel calls. Avoids pulling in a
     * real Spring TaskScheduler.
     */
    private static final class RecordingScheduler {
        final List<ScheduledJob> registered = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();

        JobScheduler asJobScheduler() {
            return new JobScheduler(null, null, null, null) {
                @Override
                public void register(ScheduledJob job) {
                    registered.add(job);
                }

                @Override
                public void cancel(String jobId) {
                    cancelled.add(jobId);
                }
            };
        }
    }
}
