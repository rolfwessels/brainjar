package brainjar.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "brainjar.scheduling")
public record ScheduleProperties(String timezone, Integer poolSize) {

    public ScheduleProperties {
        if (timezone == null || timezone.isBlank()) {
            timezone = "UTC";
        }
        if (poolSize == null || poolSize < 1) {
            poolSize = 2;
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
