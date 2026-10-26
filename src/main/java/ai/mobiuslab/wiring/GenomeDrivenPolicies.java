package ai.mobiuslab.wiring;

import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.genome.Genome;
import ai.mobiuslab.genome.GenomeActivatedEvent;
import ai.mobiuslab.genome.GenomeService;
import ai.mobiuslab.genome.WorkspaceSpec;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Glue that turns each agent's active genome into runtime policy: which memory backend it uses,
 * whose workspace it works in and which lab tools it may call. Active
 * genomes are cached and invalidated on {@link GenomeActivatedEvent}.
 */
@Configuration
public class GenomeDrivenPolicies {

    private static final Logger log = LoggerFactory.getLogger(GenomeDrivenPolicies.class);

    private final GenomeService genomes;
    private final Cache<String, Optional<Genome>> activeCache = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();

    public GenomeDrivenPolicies(@Lazy GenomeService genomes) {
        this.genomes = genomes;
    }

    /** Agent id to memory backend id, from {@code MemorySpec.backend} (default "obsidian"). */
    @Bean(name = "memoryBackendResolver")
    public Function<String, String> memoryBackendResolver() {
        return agentId -> active(agentId).map(g -> g.memory().backend()).orElse("obsidian");
    }

    /** Agent id to the agent whose workspace it uses ({@code WorkspaceSpec.SHARED_WITH}). */
    @Bean(name = "workspaceOwnerResolver")
    public Function<String, String> workspaceOwnerResolver() {
        return agentId -> active(agentId)
                .filter(g -> g.workspace().mode() == WorkspaceSpec.Mode.SHARED_WITH && g.workspace().shareWithAgentId() != null)
                .map(g -> g.workspace().shareWithAgentId())
                .orElse(agentId);
    }

    /** (agentId, toolId) allowed iff the agent's active genome grants the tool; unknown agents are denied. */
    @Bean(name = "labPermission")
    public BiPredicate<String, String> labPermission() {
        return (agentId, toolId) -> {
            Optional<Genome> g = active(agentId);
            boolean allowed = g.map(x -> x.permissions() != null
                    && (x.permissions().labs().contains(toolId) || x.permissions().tools().contains(toolId))).orElse(false);
            if (!allowed) {
                log.info("lab permission denied agent={} tool={} (genome {})", agentId, toolId, g.map(Genome::id).orElse("none"));
            }
            return allowed;
        };
    }

    /** Drops the cached genome whenever an agent's active genome changes. */
    @EventListener
    public void onGenomeActivated(GenomeActivatedEvent event) {
        activeCache.invalidate(event.agentId());
    }

    private Optional<Genome> active(String agentId) {
        return activeCache.get(agentId, id -> {
            try {
                return Optional.ofNullable(genomes.active(id));
            } catch (MobiusException e) {
                return Optional.empty();
            }
        });
    }
}
