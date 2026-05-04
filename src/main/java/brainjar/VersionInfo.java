package brainjar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Logs the running BrainJar version at startup. The version is baked into
 * {@code version.json} on the classpath at build time (see Dockerfile.deploy
 * and the {@code version} Makefile target). When the file is absent (e.g. a
 * developer running {@code ./gradlew bootRun} without first executing
 * {@code make version}), we fall back to "dev".
 */
@Component
public class VersionInfo {

    private static final Logger log = LoggerFactory.getLogger(VersionInfo.class);
    private static final String RESOURCE = "version.json";
    private static final String FALLBACK = "dev";

    private final String version;

    public VersionInfo() {
        this.version = readVersion();
    }

    public String version() {
        return version;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logVersionOnStartup() {
        log.info("BrainJar version: {}", version);
    }

    private static String readVersion() {
        var resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return FALLBACK;
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode v = root.get("version");
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        } catch (Exception e) {
            log.warn("Failed to read {}: {}", RESOURCE, e.getMessage());
        }
        return FALLBACK;
    }
}
