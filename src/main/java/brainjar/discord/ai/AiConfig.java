package brainjar.discord.ai;

import brainjar.recall.RecallTool;
import brainjar.recall.search.LayeredContext;
import brainjar.schedule.ScheduleProperties;
import brainjar.schedule.ScheduleTool;
import brainjar.skill.SkillDescriptor;
import brainjar.skill.SkillRegistry;
import brainjar.skill.SkillTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Configuration
@EnableConfigurationProperties(BraveProperties.class)
public class AiConfig {

    private static final String SYSTEM_MESSAGE_SEPARATOR = "\n\n---\n\n";
    static final int SKILLS_CATALOGUE_MAX = 20;

    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryRegistry registry) {
        return registry::getOrCreate;
    }

    @Bean
    BrainJarAssistant brainJarAssistant(
            ChatModel chatModel,
            ChatMemoryProvider chatMemoryProvider,
            BraveSearchTool braveSearchTool,
            RecallTool recallTool,
            ScheduleTool scheduleTool,
            SkillTool skillTool,
            SkillRegistry skillRegistry,
            ScheduleProperties scheduleProperties,
            LayeredContext layeredContext,
            @Value("classpath:soul.md") Resource soul,
            @Value("classpath:instructions.md") Resource instructions) {
        var soulText = readResource(soul);
        var instructionsText = readResource(instructions);
        return AiServices.builder(BrainJarAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(braveSearchTool, recallTool, scheduleTool, skillTool)
                .systemMessageProvider(memoryId ->
                        buildSystemMessage(soulText, instructionsText, layeredContext,
                                skillRegistry, memoryId, scheduleProperties))
                .build();
    }

    private static String buildSystemMessage(String soul, String instructions,
                                             LayeredContext layered,
                                             SkillRegistry skillRegistry,
                                             Object memoryId,
                                             ScheduleProperties scheduleProperties) {
        var sb = new StringBuilder()
                .append(soul)
                .append(SYSTEM_MESSAGE_SEPARATOR)
                .append(instructions)
                .append(SYSTEM_MESSAGE_SEPARATOR)
                .append(buildNowBlock(scheduleProperties));
        var skillsBlock = buildSkillsBlock(skillRegistry, memoryId);
        if (!skillsBlock.isBlank()) {
            sb.append(SYSTEM_MESSAGE_SEPARATOR).append(skillsBlock);
        }
        var userId = memoryId == null ? "anonymous" : memoryId.toString();
        var briefing = layered.briefing(userId);
        if (!briefing.isBlank()) {
            sb.append(SYSTEM_MESSAGE_SEPARATOR).append(briefing);
        }
        return sb.toString();
    }

    static String buildSkillsBlock(SkillRegistry registry, Object memoryId) {
        var userId = memoryId == null ? "anonymous" : memoryId.toString();
        List<SkillDescriptor> skills = registry.list(userId);
        if (skills.isEmpty()) {
            return "";
        }
        var visible = skills.size() > SKILLS_CATALOGUE_MAX
                ? skills.subList(0, SKILLS_CATALOGUE_MAX)
                : skills;

        var sb = new StringBuilder("## Available skills\n");
        sb.append("Before improvising a multi-step workflow, scan this list. ")
                .append("Call useSkill(name) to read the playbook.\n");
        for (var skill : visible) {
            sb.append("- ").append(skill.name()).append(": ");
            var desc = skill.description().isBlank() ? "(no description)" : skill.description();
            sb.append(desc).append("\n");
        }
        if (skills.size() > SKILLS_CATALOGUE_MAX) {
            sb.append("- +").append(skills.size() - SKILLS_CATALOGUE_MAX).append(" more (call useSkill by name)\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String buildNowBlock(ScheduleProperties scheduleProperties) {
        var now = ZonedDateTime.now(scheduleProperties.zoneId());
        var formatted = now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm z"));
        return "## Now\nCurrent local time: " + formatted
                + "\nUse this when scheduling reminders (ISO format: "
                + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                + " is the current local time).";
    }

    private static String readResource(Resource resource) {
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read resource: " + resource, e);
        }
    }
}
