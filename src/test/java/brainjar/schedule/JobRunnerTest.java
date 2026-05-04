package brainjar.schedule;

import brainjar.discord.ai.BrainJarAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobRunnerTest {

    private BrainJarAssistant assistant;
    private RecordingDeliverer deliverer;
    private InMemoryJobStore store;
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        assistant = mock(BrainJarAssistant.class);
        deliverer = new RecordingDeliverer();
        store = new InMemoryJobStore();
        var properties = new ScheduleProperties("UTC", 2);
        runner = new JobRunner(assistant, deliverer, store, properties);
    }

    @Test
    void fire_ShouldCallAssistantAndDeliverReply() {
        var job = ScheduledJob.once("job_1", "user-1", "remind me", null,
                Instant.parse("2027-01-01T10:00:00Z"), Instant.now());
        store.save(job);
        when(assistant.chat(eq("user-1"), startsWith("[scheduled reminder")))
                .thenReturn("Hey, here's your reminder.");

        runner.fire(job);

        assertThat(deliverer.messages).hasSize(1);
        assertThat(deliverer.messages.getFirst().userId).isEqualTo("user-1");
        assertThat(deliverer.messages.getFirst().text).isEqualTo("Hey, here's your reminder.");
    }

    @Test
    void fire_WhenOnce_ShouldDeleteJobFromStore() {
        var job = ScheduledJob.once("job_1", "user-1", "remind me", null,
                Instant.parse("2027-01-01T10:00:00Z"), Instant.now());
        store.save(job);
        when(assistant.chat(eq("user-1"), startsWith("[scheduled reminder"))).thenReturn("ok");

        runner.fire(job);

        assertThat(store.findById("job_1")).isEmpty();
    }

    @Test
    void fire_WhenCron_ShouldKeepJobInStore() {
        var job = ScheduledJob.recurring("job_r", "user-1", "daily", null,
                "0 0 9 * * *", Instant.now());
        store.save(job);
        when(assistant.chat(eq("user-1"), startsWith("[scheduled reminder"))).thenReturn("ok");

        runner.fire(job);

        assertThat(store.findById("job_r")).isPresent();
    }

    @Test
    void notifyMissed_ShouldDeliverMessageAndDeleteOnceJobWithoutCallingAssistant() {
        var job = ScheduledJob.once("job_m", "user-1", "take your meds", "meds",
                Instant.parse("2026-01-01T07:30:00Z"), Instant.now());
        store.save(job);

        runner.notifyMissed(job);

        assertThat(deliverer.messages).hasSize(1);
        var delivered = deliverer.messages.getFirst();
        assertThat(delivered.userId).isEqualTo("user-1");
        assertThat(delivered.text)
                .containsIgnoringCase("missed")
                .contains("2026-01-01 07:30")
                .contains("meds")
                .contains("take your meds");
        assertThat(store.findById("job_m")).isEmpty();
        verify(assistant, never()).chat(anyString(), anyString());
    }

    @Test
    void notifyMissed_WhenCronJob_ShouldBeIgnored() {
        var job = ScheduledJob.recurring("job_cm", "user-1", "daily check", null,
                "0 0 9 * * *", Instant.now());
        store.save(job);

        runner.notifyMissed(job);

        assertThat(deliverer.messages).isEmpty();
        assertThat(store.findById("job_cm")).isPresent();
    }

    @Test
    void fire_WhenAssistantThrows_ShouldNotPropagateOrDeleteCronJob() {
        var job = ScheduledJob.recurring("job_r", "user-1", "daily", null,
                "0 0 9 * * *", Instant.now());
        store.save(job);
        when(assistant.chat(eq("user-1"), startsWith("[scheduled reminder")))
                .thenThrow(new RuntimeException("boom"));

        runner.fire(job);

        assertThat(store.findById("job_r")).isPresent();
        assertThat(deliverer.messages).isEmpty();
    }

    private static final class RecordingDeliverer implements MessageDeliverer {
        final List<Delivered> messages = new ArrayList<>();

        @Override
        public void deliver(String userId, String message) {
            messages.add(new Delivered(userId, message));
        }

        record Delivered(String userId, String text) {}
    }
}
