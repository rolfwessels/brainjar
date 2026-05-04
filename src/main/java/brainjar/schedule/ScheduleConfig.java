package brainjar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(ScheduleProperties.class)
public class ScheduleConfig {

    private static final Logger log = LoggerFactory.getLogger(ScheduleConfig.class);
    private static final String DEFAULT_SCHEDULES_PATH = ".recall/schedules.json";
    public static final String SCHEDULER_BEAN = "brainjarTaskScheduler";

    @Bean
    JobStore jobStore(@Value("${brainjar.scheduling.path:}") String configuredPath) {
        var path = resolveSchedulesPath(configuredPath);
        log.info("Schedules file: {}", path);
        return new FileJobStore(path);
    }

    @Bean(name = SCHEDULER_BEAN, destroyMethod = "shutdown")
    TaskScheduler brainjarTaskScheduler(ScheduleProperties properties) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.poolSize());
        scheduler.setThreadNamePrefix("brainjar-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.initialize();
        return scheduler;
    }

    private static Path resolveSchedulesPath(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), DEFAULT_SCHEDULES_PATH);
    }
}
