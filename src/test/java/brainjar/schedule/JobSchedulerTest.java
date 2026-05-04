package brainjar.schedule;

import brainjar.discord.ai.BrainJarAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobSchedulerTest {

    private ThreadPoolTaskScheduler taskScheduler;
    private InMemoryJobStore store;
    private LatchingDeliverer deliverer;
    private JobScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        store = new InMemoryJobStore();
        deliverer = new LatchingDeliverer();
        var assistant = mock(BrainJarAssistant.class);
        when(assistant.chat(anyString(), anyString())).thenReturn("ok");
        var properties = new ScheduleProperties("UTC", 2);
        var runner = new JobRunner(assistant, deliverer, store, properties);
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof JobFiredEvent fired) {
                runner.fire(fired.job());
            } else if (event instanceof JobMissedEvent missed) {
                runner.notifyMissed(missed.job());
            }
        };
        scheduler = new JobScheduler(taskScheduler, publisher, store, properties);
    }

    @AfterEach
    void tearDown() {
        taskScheduler.shutdown();
    }

    @Test
    void register_ShouldFireOnceJobAndRemoveFromStore() throws InterruptedException {
        deliverer.expect(1);
        var job = ScheduledJob.once("job_1", "user-1", "remind me", null,
                Instant.now().plusMillis(200), Instant.now());
        store.save(job);

        scheduler.register(job);

        assertThat(deliverer.latch.await(3, TimeUnit.SECONDS))
                .as("expected one-shot job to fire within 3 seconds")
                .isTrue();
        assertThat(store.findById("job_1")).isEmpty();
    }

    @Test
    void register_WhenOneShotInPast_ShouldNotifyUserAndRemoveFromStore() {
        var job = ScheduledJob.once("job_missed", "user-1", "take your meds", "meds",
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(3600));
        store.save(job);

        scheduler.register(job);

        assertThat(store.findById("job_missed")).isEmpty();
        assertThat(deliverer.messages).hasSize(1);
        assertThat(deliverer.messages.getFirst())
                .containsIgnoringCase("missed")
                .contains("take your meds");
    }

    @Test
    void register_WhenOneShotLongInPast_ShouldStillNotify() {
        var job = ScheduledJob.once("job_old", "user-1", "old reminder", null,
                Instant.now().minusSeconds(3600 * 24 * 7), Instant.now().minusSeconds(3600 * 24 * 7));
        store.save(job);

        scheduler.register(job);

        assertThat(store.findById("job_old")).isEmpty();
        assertThat(deliverer.messages).hasSize(1);
    }

    @Test
    void cancel_ShouldPreventOneShotFiring() throws InterruptedException {
        var job = ScheduledJob.once("job_c", "user-1", "remind me", null,
                Instant.now().plusMillis(500), Instant.now());
        store.save(job);
        scheduler.register(job);

        scheduler.cancel(job.id());
        store.delete(job.id());

        TimeUnit.MILLISECONDS.sleep(800);
        assertThat(deliverer.messages).isEmpty();
    }

    private static final class LatchingDeliverer implements MessageDeliverer {
        final List<String> messages = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        void expect(int count) {
            latch = new CountDownLatch(count);
        }

        @Override
        public synchronized void deliver(String userId, String message) {
            messages.add(message);
            latch.countDown();
        }
    }
}
