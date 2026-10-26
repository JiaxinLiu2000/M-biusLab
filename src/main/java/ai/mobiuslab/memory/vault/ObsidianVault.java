package ai.mobiuslab.memory.vault;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import ai.mobiuslab.memory.MemoryNote;
import ai.mobiuslab.memory.MemoryScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Obsidian vault on disk: {@code public/}, {@code groups/<g>/}, {@code agents/<id>/}; one Markdown
 * file per note with YAML frontmatter and a {@code ## Links} section of wikilinks. Writes are atomic
 * (temp file + move) and serialized per note via striped locks; reads need no lock.
 */
@Component
public class ObsidianVault {

    private static final String LINKS_HEADER = "\n## Links\n";
    private static final int STRIPES = 64;

    private final Path root;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    /** Spring constructor: root is {@code ${mobius.home}/vault}. */
    @Autowired
    public ObsidianVault(MobiusProperties props) {
        this(props.homePath().resolve("vault"));
    }

    /** Creates a vault at an explicit root (tests). */
    public ObsidianVault(Path root) {
        this.root = root.toAbsolutePath().normalize();
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** Vault root directory. */
    public Path root() {
        return root;
    }

    /** Vault-relative path (forward slashes) where a note is stored. */
    public String relativePath(MemoryNote note) {
        String dir = switch (note.scope()) {
            case PUBLIC -> "public";
            case GROUP -> "groups/" + safe(note.groupId());
            case PRIVATE -> "agents/" + safe(note.ownerAgentId());
        };
        return dir + "/" + stem(note) + ".md";
    }

    /** File stem used for wikilinks: {@code <slug(title)>-<noteId>}. */
    public static String stem(MemoryNote note) {
        return stem(note.title(), note.id());
    }

    /** File stem for a title and id. */
    public static String stem(String title, String id) {
        return slug(title) + "-" + id;
    }

    /** Writes (or atomically rewrites) a note; {@code linkStem} maps linked note ids to file stems. */
    public String write(MemoryNote note, Function<String, String> linkStem) {
        String rel = relativePath(note);
        Path target = root.resolve(rel);
        String content = render(note, linkStem);
        ReentrantLock lock = locks[Math.floorMod(note.id().hashCode(), STRIPES)];
        lock.lock();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".tmp-" + note.id(), ".md");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Vault write failed for " + note.id() + " at " + rel, e);
        } finally {
            lock.unlock();
        }
        return rel;
    }

    /** Reads a note from a vault-relative path. */
    public MemoryNote read(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw MobiusException.invalid("Path escapes vault: " + relativePath);
        }
        try {
            return parse(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.NOT_FOUND, "Vault note unreadable: " + relativePath, e);
        }
    }

    /** Lists every note file in the vault (for rebuilds). */
    public List<Path> listFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var s = Files.walk(root)) {
            return s.filter(f -> f.toString().endsWith(".md") && !f.getFileName().toString().startsWith(".tmp-")).toList();
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Vault listing failed", e);
        }
    }

    /** Renders a note as Markdown with frontmatter and links section. */
    public String render(MemoryNote n, Function<String, String> linkStem) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", n.id());
        fm.put("title", n.title());
        fm.put("scope", n.scope().name());
        fm.put("owner", n.ownerAgentId());
        fm.put("group", n.groupId());
        fm.put("kind", n.kind());
        fm.put("tags", n.tags() == null ? List.of() : n.tags());
        fm.put("source", n.source());
        fm.put("confidence", n.confidence());
        fm.put("created", n.createdAt() == null ? null : n.createdAt().toString());
        fm.put("invalidated", n.invalidated());
        fm.put("superseded_by", n.supersededBy());
        fm.put("links", n.links() == null ? List.of() : n.links());
        StringBuilder sb = new StringBuilder("---\n");
        try {
            sb.append(yaml.writeValueAsString(fm));
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Frontmatter render failed for " + n.id(), e);
        }
        sb.append("---\n\n# ").append(n.title()).append("\n\n").append(n.body() == null ? "" : n.body().strip()).append('\n');
        List<String> links = n.links() == null ? List.of() : n.links();
        if (!links.isEmpty() || n.supersededBy() != null) {
            sb.append(LINKS_HEADER);
            for (String id : links) {
                sb.append("- [[").append(linkStem.apply(id)).append("]]\n");
            }
            if (n.supersededBy() != null) {
                sb.append("- superseded by [[").append(linkStem.apply(n.supersededBy())).append("]]\n");
            }
        }
        return sb.toString();
    }

    /** Parses Markdown produced by {@link #render}. */
    @SuppressWarnings("unchecked")
    public MemoryNote parse(String text) {
        if (!text.startsWith("---\n")) {
            throw MobiusException.invalid("Note has no frontmatter");
        }
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            throw MobiusException.invalid("Unterminated frontmatter");
        }
        Map<String, Object> fm;
        try {
            fm = yaml.readValue(text.substring(4, end + 1), Map.class);
        } catch (IOException e) {
            throw MobiusException.invalid("Bad frontmatter: " + e.getMessage());
        }
        String rest = text.substring(end + 5);
        int links = rest.lastIndexOf(LINKS_HEADER);
        String body = links >= 0 ? rest.substring(0, links) : rest;
        body = body.strip();
        String title = str(fm.get("title"));
        String heading = "# " + title;
        if (body.startsWith(heading)) {
            body = body.substring(heading.length()).strip();
        }
        Object conf = fm.get("confidence");
        String created = str(fm.get("created"));
        return new MemoryNote(str(fm.get("id")), MemoryScope.valueOf(str(fm.get("scope"))), str(fm.get("owner")),
                str(fm.get("group")), str(fm.get("kind")), title, body, strList(fm.get("tags")),
                strList(fm.get("links")), str(fm.get("source")),
                conf == null ? 0.5 : Double.parseDouble(conf.toString()),
                created == null ? null : Instant.parse(created),
                Boolean.parseBoolean(String.valueOf(fm.get("invalidated"))), str(fm.get("superseded_by")));
    }

    /** URL/file-safe lowercase slug, max 60 chars. */
    public static String slug(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "note" : s;
    }

    private static String safe(String segment) {
        if (segment == null || segment.isBlank()) {
            throw MobiusException.invalid("Missing vault path segment");
        }
        return segment.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }
}
