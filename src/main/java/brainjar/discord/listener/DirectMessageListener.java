package brainjar.discord.listener;

import brainjar.context.UserContext;
import brainjar.discord.ErrorReplies;
import brainjar.discord.MessageSplitter;
import brainjar.discord.ai.BrainJarAssistant;
import brainjar.discord.voice.VoiceTranscriber;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;

@Component
public class DirectMessageListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DirectMessageListener.class);
    private static final int LOG_PREVIEW_LENGTH = 80;

    private final BrainJarAssistant assistant;
    private final VoiceTranscriber voiceTranscriber;

    public DirectMessageListener(BrainJarAssistant assistant, VoiceTranscriber voiceTranscriber) {
        this.assistant = assistant;
        this.voiceTranscriber = voiceTranscriber;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.PRIVATE)) return;
        if (event.getAuthor().isBot()) return;

        var userId = event.getAuthor().getId();
        var username = event.getAuthor().getName();
        var message = event.getMessage();
        var channel = event.getChannel();

        String userInput;
        if (message.isVoiceMessage()) {
            userInput = handleVoiceMessage(message, username, channel);
            if (userInput == null) return;
        } else {
            userInput = message.getContentDisplay();
            log.info("DM received from {}: \"{}\"", username, preview(userInput));
        }

        channel.sendTyping().queue();
        sleep(1500);

        UserContext.set(userId);
        try {
            var response = assistant.chat(userId, userInput);
            deliver(channel, username, response);
        } catch (RuntimeException e) {
            log.error("Assistant call failed for {}", username, e);
            sendApology(channel, username);
        } finally {
            UserContext.clear();
        }
    }

    private String handleVoiceMessage(Message message, String username, MessageChannel channel) {
        var attachment = message.getAttachments().stream()
                .filter(a -> a.getContentType() != null && a.getContentType().startsWith("audio/"))
                .findFirst()
                .orElse(null);
        if (attachment == null) {
            log.warn("Voice message from {} had no audio attachment", username);
            sendPlain(channel, username, "that voice note came through without audio — mind resending?");
            return null;
        }

        byte[] audio;
        try {
            try (var in = attachment.getProxy().download().join()) {
                audio = in.readAllBytes();
            }
        } catch (CompletionException | java.io.IOException e) {
            log.error("Failed to download voice attachment from {}", username, e);
            sendPlain(channel, username, "couldn't download that voice note — try again?");
            return null;
        }

        var result = voiceTranscriber.transcribe(audio, attachment.getContentType(), attachment.getDuration());
        return switch (result) {
            case VoiceTranscriber.Success s -> {
                log.info("DM voice transcribed from {} ({}s, {} bytes): \"{}\"",
                        username, attachment.getDuration(), audio.length, preview(s.text()));
                yield s.text();
            }
            case VoiceTranscriber.Blank b -> {
                log.info("Voice message from {} transcribed blank", username);
                sendPlain(channel, username, "couldn't make out that voice note — try again?");
                yield null;
            }
            case VoiceTranscriber.TooLarge t -> {
                log.warn("Voice message from {} rejected: {}", username, t.reason());
                sendPlain(channel, username, "that voice note is too big for me — keep it under 24 MB?");
                yield null;
            }
            case VoiceTranscriber.TooLong t -> {
                log.warn("Voice message from {} rejected: {}", username, t.reason());
                sendPlain(channel, username, "that voice note is too long — keep it under 5 minutes?");
                yield null;
            }
            case VoiceTranscriber.Failed f -> {
                log.error("Voice transcription failed for {}: {}", username, f.reason());
                sendApology(channel, username);
                yield null;
            }
        };
    }

    private void sendPlain(MessageChannel channel, String username, String text) {
        channel.sendMessage(text).queue(
                success -> log.info("DM sent to {}: \"{}\"", username, preview(text)),
                failure -> log.error("Failed to send DM to {}: {}", username, failure.getMessage())
        );
    }

    private void sendApology(MessageChannel channel, String username) {
        var reply = ErrorReplies.pick();
        channel.sendMessage(reply).queue(
                success -> log.info("DM apology sent to {}: \"{}\"", username, preview(reply)),
                failure -> log.error("Failed to send apology to {}: {}", username, failure.getMessage())
        );
    }

    private void deliver(MessageChannel channel, String username, String response) {
        var result = MessageSplitter.split(response);
        switch (result) {
            case MessageSplitter.FileAttachment attachment -> sendFile(channel, username, attachment);
            case MessageSplitter.Messages messages -> sendChunks(channel, username, messages.chunks());
        }
    }

    private void sendFile(MessageChannel channel, String username, MessageSplitter.FileAttachment attachment) {
        var bytes = attachment.content().getBytes(StandardCharsets.UTF_8);
        channel.sendFiles(FileUpload.fromData(bytes, attachment.filename())).queue(
                success -> log.info("DM sent file to {}: {} ({} bytes)",
                        username, attachment.filename(), bytes.length),
                failure -> log.error("Failed to send file to {}: {}", username, failure.getMessage())
        );
    }

    private void sendChunks(MessageChannel channel, String username, List<String> chunks) {
        for (var chunk : chunks) {
            channel.sendMessage(chunk).queue(
                    success -> log.info("DM sent to {}: \"{}\"", username, preview(chunk)),
                    failure -> log.error("Failed to send DM to {}: {}", username, failure.getMessage())
            );
        }
    }

    private String preview(String text) {
        if (text.length() <= LOG_PREVIEW_LENGTH) return text;
        return text.substring(0, LOG_PREVIEW_LENGTH) + "...";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
