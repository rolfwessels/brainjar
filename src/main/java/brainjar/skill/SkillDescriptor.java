package brainjar.skill;

/**
 * A single skill Perry can call on. Built-in skills ship with the app on the
 * classpath; user skills are taught at runtime via {@code teachSkill} and
 * stored on a per-user shelf.
 *
 * @param name        unique slug, matched verbatim by {@code useSkill(name)}
 * @param description one-line "when to use" hint surfaced in the system prompt
 * @param body        full SKILL.md text (frontmatter + body), returned by {@code useSkill}
 * @param origin      where this skill came from
 */
public record SkillDescriptor(String name, String description, String body, Origin origin) {

    public enum Origin {
        BUILT_IN,
        USER
    }

    public SkillDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("Skill description must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("Skill body must not be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("Skill origin must not be null");
        }
    }
}
