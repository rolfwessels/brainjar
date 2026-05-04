package brainjar.schedule;

import brainjar.discord.MessageSplitter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DiscordMessageDeliverer implements MessageDeliverer {

    private static final Logger log = LoggerFactory.getLogger(DiscordMessageDeliverer.class);
    private static final int PREVIEW_LEN = 80;

    private final JDA jda;

    public DiscordMessageDeliverer(JDA jda) {
        this.jda = jda;
    }

    @Override
    public void deliver(String userId, String message) {
        if (message == null || message.isBlank()) {
            log.warn("Skipping DM to {} — message was blank", userId);
            return;
        }
        try {
            var user = jda.retrieveUserById(userId).complete();
            var channel = user.openPrivateChannel().complete();
            var split = MessageSplitter.split(message);
            switch (split) {
                case MessageSplitter.FileAttachment file -> sendFile(channel, userId, file);
                case MessageSplitter.Messages msgs -> {
                    for (var chunk : msgs.chunks()) {
                        channel.sendMessage(chunk).complete();
                        log.info("Scheduled DM sent to {}: \"{}\"", userId, preview(chunk));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("Failed to deliver scheduled DM to {}", userId, e);
        }
    }

    private void sendFile(MessageChannel channel, String userId, MessageSplitter.FileAttachment file) {
        var bytes = file.content().getBytes(StandardCharsets.UTF_8);
        channel.sendFiles(FileUpload.fromData(bytes, file.filename())).complete();
        log.info("Scheduled DM file sent to {}: {} ({} bytes)", userId, file.filename(), bytes.length);
    }

    private static String preview(String text) {
        if (text.length() <= PREVIEW_LEN) return text;
        return text.substring(0, PREVIEW_LEN) + "...";
    }
}
