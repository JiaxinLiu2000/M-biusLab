package ai.mobiuslab.genome;

import ai.mobiuslab.memory.MemoryAccessPolicy;
import ai.mobiuslab.memory.MemoryScope;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves memory access from the agent's active genome permissions. PRIVATE is always READ_WRITE for the
 * owner; MASTER agents default to PUBLIC READ_WRITE; group members default to READ on their groups.
 */
@Component
public class GenomeMemoryAccessPolicy implements MemoryAccessPolicy {

    private record Grants(Map<String, Access> access) {
    }

    private final GenomeService genomes;
    private final JdbcClient jdbc;
    private final Cache<String, Grants> cache = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10)).build();

    public GenomeMemoryAccessPolicy(GenomeService genomes, JdbcClient jdbc) {
        this.genomes = genomes;
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Access accessFor(String agentId, MemoryScope scope, String groupId) {
        if (scope == MemoryScope.PRIVATE) {
            return Access.READ_WRITE;
        }
        Grants g = cache.get(agentId, this::load);
        return g.access().getOrDefault(scope.key(groupId), Access.NONE);
    }

    /** Drops cached grants when an agent's active genome changes. */
    @EventListener
    public void onActivated(GenomeActivatedEvent event) {
        cache.invalidate(event.agentId());
    }

    /** Drops all cached grants. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private Grants load(String agentId) {
        Map<String, Access> access = new HashMap<>();
        String kind = jdbc.sql("SELECT kind FROM agent WHERE id = :id AND deleted = 0").param("id", agentId)
                .query(String.class).optional().orElse(null);
        if ("MASTER".equals(kind)) {
            access.put(MemoryScope.PUBLIC.key(null), Access.READ_WRITE);
        }
        Genome genome;
        try {
            genome = genomes.active(agentId);
        } catch (ai.mobiuslab.common.MobiusException e) {
            return new Grants(access);
        }
        for (String group : genome.memory().groups()) {
            access.put(MemoryScope.GROUP.key(group), Access.READ);
        }
        if (genome.permissions() != null) {
            access.putAll(genome.permissions().memory());
        }
        return new Grants(Map.copyOf(access));
    }
}
