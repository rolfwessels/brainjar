package brainjar.skill;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import brainjar.recall.model.Shelf;
import brainjar.recall.store.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads, lists, and reads back skills. Built-in skills are scanned once at
 * startup from {@code classpath:skills/*&#47;SKILL.md}. User-taught skills
 * live in {@link PageStore} on a per-user shelf {@code user:<uid>:skills},
 * one book per skill at {@code captures/<uid>/skills/<slug>.md}.
 *
 * <p>Catalogue listing ({@link #list(String)}) merges both sources, with
 * built-in skills winning on a name collision so the catalogue stays
 * predictable across deploys.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    static final String BUILTIN_PATTERN = "classpath*:skills/*/SKILL.md";
    static final String USER_SHELF_PREFIX = "user:";
    static final String USER_SHELF_SEPARATOR = ":";
    static final String SKILLS_SHELF = "skills";
    static final String CAPTURES_ROOT = "captures";

    private final PageStore pageStore;
    private final Map<String, SkillDescriptor> builtIns;

    @Autowired
    public SkillRegistry(PageStore pageStore) {
        this(pageStore, new PathMatchingResourcePatternResolver());
    }

    public SkillRegistry(PageStore pageStore, ResourcePatternResolver resolver) {
        this.pageStore = pageStore;
        this.builtIns = loadBuiltIns(resolver);
        log.info("SkillRegistry loaded built-in skills: {}", builtIns.keySet());
    }

    /**
     * Catalogue for the given user — every built-in plus any skills they have
     * taught. Built-in wins on a name collision; the user's shadowed entry
     * remains in storage but is omitted here. Sorted by name.
     */
    public List<SkillDescriptor> list(String userId) {
        var merged = new LinkedHashMap<String, SkillDescriptor>(builtIns);
        for (var taught : userTaught(userId)) {
            merged.putIfAbsent(taught.name(), taught);
        }
        return merged.values().stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /**
     * Look up a single skill's full descriptor (body included) by name.
     * Built-in is preferred over user-taught on a name collision.
     */
    public Optional<SkillDescriptor> find(String userId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        var slug = slug(name);
        var builtIn = builtIns.get(slug);
        if (builtIn != null) {
            return Optional.of(builtIn);
        }
        return findUserTaught(userId, slug);
    }

    /**
     * Persist a user-taught skill. Re-teaching the same name overwrites the
     * existing page (deterministic page id from book path).
     */
    public SkillDescriptor teach(String userId, String name, String description, String body) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be blank");
        }
        var slug = slug(name);
        var desc = description == null ? "" : description.strip();
        var bodyText = body == null ? "" : body.strip();

        var content = renderSkillFile(slug, desc, bodyText);
        var book = userSkillBook(userId, slug);
        var pageId = Page.generateId(book.sourcePath().toString(), 0);
        var page = new Page(pageId, content, 0, book);
        pageStore.store(List.of(page));

        log.info("teachSkill user={} name={} chars={}", userId, slug, content.length());
        return new SkillDescriptor(slug, desc, content, SkillDescriptor.Origin.USER);
    }

    /**
     * Normalise a free-form skill name into a stable lower-kebab-case slug.
     * Same rules as recall shelf normalisation so the conventions don't drift.
     */
    public static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        var cleaned = raw.strip().toLowerCase().replaceAll("[^a-z0-9-]+", "-");
        cleaned = cleaned.replaceAll("(^-+)|(-+$)", "");
        return cleaned;
    }

    private List<SkillDescriptor> userTaught(String userId) {
        var shelfName = userSkillsShelf(userId);
        var pages = pageStore.recent(Integer.MAX_VALUE).stream()
                .filter(p -> shelfName.equals(p.book().shelf().name()))
                .toList();
        var out = new ArrayList<SkillDescriptor>();
        for (var page : pages) {
            try {
                out.add(parseSkill(page.content(), SkillDescriptor.Origin.USER));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping malformed user-taught skill page id={} reason={}",
                        page.id(), e.getMessage());
            }
        }
        return out;
    }

    private Optional<SkillDescriptor> findUserTaught(String userId, String slug) {
        var book = userSkillBook(userId, slug);
        var pageId = Page.generateId(book.sourcePath().toString(), 0);
        return pageStore.findById(pageId).map(p -> {
            try {
                return parseSkill(p.content(), SkillDescriptor.Origin.USER);
            } catch (IllegalArgumentException e) {
                log.warn("Malformed user-taught skill page id={} reason={}",
                        p.id(), e.getMessage());
                return null;
            }
        });
    }

    private static Map<String, SkillDescriptor> loadBuiltIns(ResourcePatternResolver resolver) {
        var map = new LinkedHashMap<String, SkillDescriptor>();
        Resource[] resources;
        try {
            resources = resolver.getResources(BUILTIN_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan classpath for built-in skills: {}", e.getMessage());
            return map;
        }
        for (var resource : resources) {
            try (var is = resource.getInputStream()) {
                var content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                var skill = parseSkill(content, SkillDescriptor.Origin.BUILT_IN);
                map.put(skill.name(), skill);
            } catch (IOException e) {
                log.warn("Failed to read built-in skill {}: {}", resource, e.getMessage());
            } catch (IllegalArgumentException e) {
                log.warn("Skipping malformed built-in skill {}: {}", resource, e.getMessage());
            }
        }
        return map;
    }

    static SkillDescriptor parseSkill(String content, SkillDescriptor.Origin origin) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Skill content is empty");
        }
        var frontmatter = parseFrontmatter(content);
        var name = frontmatter.get("name");
        var description = frontmatter.getOrDefault("description", "");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill is missing 'name' frontmatter");
        }
        return new SkillDescriptor(slug(name), description, content, origin);
    }

    /**
     * Minimal YAML-frontmatter parser sufficient for skill files: supports
     * inline {@code key: value} and folded scalars {@code key: >- \n  multi}.
     * Returns an empty map if no frontmatter delimiters are present.
     */
    static Map<String, String> parseFrontmatter(String text) {
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return Map.of();
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return Map.of();
        }

        var result = new LinkedHashMap<String, String>();
        String key = null;
        var buf = new StringBuilder();
        boolean folded = false;

        for (int i = 1; i < end; i++) {
            var line = lines.get(i);
            var stripped = line.stripLeading();
            int colon = stripped.indexOf(':');
            boolean looksInline = stripped.length() > 0
                    && Character.isLetter(stripped.charAt(0))
                    && colon > 0
                    && !line.startsWith(" ")
                    && !line.startsWith("\t");

            if (looksInline) {
                if (key != null) {
                    result.put(key, buf.toString().strip());
                    buf.setLength(0);
                }
                key = stripped.substring(0, colon).trim();
                var rest = stripped.substring(colon + 1).trim();
                folded = rest.startsWith(">");
                if (!folded && !rest.isEmpty()) {
                    buf.append(rest);
                }
            } else if (key != null) {
                var trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (buf.length() > 0) {
                    buf.append(folded ? ' ' : '\n');
                }
                buf.append(trimmed);
            }
        }
        if (key != null) {
            result.put(key, buf.toString().strip());
        }
        return result;
    }

    /**
     * Render a SKILL.md-shaped string for a user-taught skill. Body may be
     * empty; description may be blank. Always emits frontmatter so the
     * loader path is symmetric with built-ins.
     */
    static String renderSkillFile(String slug, String description, String body) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(slug).append("\n");
        sb.append("description: ").append(description.replace("\n", " ")).append("\n");
        sb.append("---\n");
        if (!body.isBlank()) {
            sb.append("\n").append(body).append("\n");
        }
        return sb.toString();
    }

    private static String userSkillsShelf(String userId) {
        return USER_SHELF_PREFIX + userId + USER_SHELF_SEPARATOR + SKILLS_SHELF;
    }

    private static Book userSkillBook(String userId, String slug) {
        var shelf = new Shelf(userSkillsShelf(userId));
        var sourcePath = Path.of(CAPTURES_ROOT, userId, SKILLS_SHELF, slug + ".md");
        var title = "skill: " + slug;
        return new Book(sourcePath, title, shelf, Instant.now());
    }
}
