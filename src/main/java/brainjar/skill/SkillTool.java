package brainjar.skill;

import brainjar.context.UserContext;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tools Perry uses to read and author skills. Skill catalogue (name +
 * description for every available skill) is injected into the system
 * prompt by {@link brainjar.discord.ai.AiConfig}, so Perry already
 * knows what exists; these tools just fetch the body or persist a new
 * one.
 */
@Component
public class SkillTool {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Tool("Read the full body of a skill (a stored playbook for a multi-step workflow). "
            + "The list of available skills with their one-line descriptions is in the '## Available skills' "
            + "block of your system prompt — pick a name from there. Returns the SKILL.md text including "
            + "frontmatter; follow the playbook in the body.")
    public String useSkill(String name) {
        var userId = UserContext.getOrAnonymous();
        if (name == null || name.isBlank()) {
            return "No skill — name was empty.";
        }
        var found = registry.find(userId, name);
        if (found.isEmpty()) {
            log.info("useSkill user={} name=\"{}\" result=not-found", userId, name);
            return "No skill named \"" + name + "\". Check the '## Available skills' block in your system prompt for valid names.";
        }
        var skill = found.get();
        log.info("useSkill user={} name={} origin={}", userId, skill.name(), skill.origin());
        return skill.body();
    }

    @Tool("Teach Perry a new skill — a reusable playbook for a multi-step workflow you want him to be able to repeat. "
            + "`name` is a short slug (e.g. 'morning-routine'); re-teaching the same name overwrites. "
            + "`description` is a one-line 'when to use' hint that will appear in the system prompt catalogue every turn. "
            + "`body` is the playbook itself — markdown, as long as needed. "
            + "The skill is scoped to the current user. Use sparingly: only for patterns the user explicitly asks you to remember as a skill.")
    public String teachSkill(String name, String description, String body) {
        var userId = UserContext.getOrAnonymous();
        if (name == null || name.isBlank()) {
            return "Cannot teach — `name` was empty.";
        }
        try {
            var taught = registry.teach(userId, name, description, body);
            return "Taught skill \"" + taught.name() + "\". It will appear in your system prompt catalogue from the next turn onwards.";
        } catch (IllegalArgumentException e) {
            log.warn("teachSkill user={} name=\"{}\" rejected: {}", userId, name, e.getMessage());
            return "Cannot teach — " + e.getMessage();
        }
    }
}
