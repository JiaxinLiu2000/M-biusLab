package ai.mobiuslab.memory;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.Json;
import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.common.MobiusProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alternative memory backend "episodic-log": a per-agent append-only JSONL timeline under
 * {@code ${mobius.home}/memory/episodic/<agentId>.jsonl} searched by keyword overlap weighted by recency
 * (half-life one day). Appends are serialized per agent file; reads are lock-free line scans.
 */
@Component
public class EpisodicLogBackend implements MemoryBackend {

    /** Backend id. */
    public static final String ID = "episodic-log";

    private static final double HALF_LIFE_HOURS = 24;

    private final Path dir;
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    /** Spring constructor. */
    @Autowired
    public EpisodicLogBackend(MobiusProperties props) {
        this(props.homePath().resolve("memory").resolve("episodic"));
    }

    /** Explicit directory (tests). */
    public EpisodicLogBackend(Path dir) {
        this.dir = dir;
    }

    /** {@inheritDoc} */
    @Override
    public String id() {
        return ID;
    }

    /** {@inheritDoc} */
    @Override
    public void store(MemoryNote note) {
        String agent = note.ownerAgentId().replaceAll("[^A-Za-z0-9_.-]", "_");
        Path file = dir.resolve(agent + ".jsonl");
        synchronized (fileLocks.computeIfAbsent(agent, k -> new Object())) {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, Json.write(note) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new MobiusException(ErrorCode.INTERNAL, "Episodic append failed for " + agent, e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryHit> find(MemoryQuery query, List<String> allowedScopeKeys) {
        Set<String> terms = tokens(query.text());
        Instant now = Instant.now();
        List<MemoryHit> hits = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return hits;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    MemoryNote n = Json.read(line, MemoryNote.class);
                    if (!allowedScopeKeys.contains(ScopeKeys.of(n)) || (!query.includeInvalidated() && n.invalidated())
                            || (query.kinds() != null && !query.kinds().isEmpty() && !query.kinds().contains(n.kind()))) {
                        continue;
                    }
                    Set<String> nt = tokens(n.title() + " " + n.body());
                    long overlap = terms.stream().filter(nt::contains).count();
                    if (overlap == 0 && !terms.isEmpty()) {
                        continue;
                    }
                    double ageH = n.createdAt() == null ? 0 : Duration.between(n.createdAt(), now).toMinutes() / 60.0;
                    double score = (terms.isEmpty() ? 1 : (double) overlap / terms.size()) * Math.pow(0.5, ageH / HALF_LIFE_HOURS);
                    hits.add(new MemoryHit(n, score));
                }
            }
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Episodic log read failed", e);
        }
        int limit = query.limit() <= 0 ? 10 : query.limit();
        return hits.stream().sorted(Comparator.comparingDouble(MemoryHit::score).reversed()).limit(limit).toList();
    }

    private static Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        Set<String> s = new HashSet<>(Arrays.asList(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        s.remove("");
        return s;
    }
}
