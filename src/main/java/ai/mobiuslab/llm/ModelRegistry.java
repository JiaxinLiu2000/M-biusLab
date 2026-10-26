package ai.mobiuslab.llm;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Model catalog loaded from {@code classpath:models.yml}: provider, prices, context length, role defaults. */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    /** Role used when a request names no model. */
    public static final String DEFAULT_ROLE = "worker";

    /** Model provider. */
    public enum Provider { CRUSOE, JEV }

    /** One catalog entry; prices are USD per 1M tokens. */
    public record ModelInfo(String id, Provider provider, double inputPerM, double cachedInputPerM,
                            double outputPerM, int contextLength, List<String> roles) {
        public ModelInfo {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    private record Catalog(List<ModelInfo> models) {
    }

    private final Map<String, ModelInfo> byId = new LinkedHashMap<>();
    private final Map<String, String> byRole = new LinkedHashMap<>();
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    /** Loads the default {@code models.yml}. */
    public ModelRegistry() {
        this(load("models.yml"));
    }

    /** Builds a registry from explicit entries (first model per role wins). */
    public ModelRegistry(Collection<ModelInfo> models) {
        for (ModelInfo m : models) {
            byId.put(m.id(), m);
            for (String r : m.roles()) {
                byRole.putIfAbsent(r, m.id());
            }
        }
        log.info("model registry loaded {} models, roles {}", byId.size(), byRole);
    }

    private static List<ModelInfo> load(String resource) {
        try (InputStream in = ModelRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new MobiusException(ErrorCode.INTERNAL, "classpath resource missing: " + resource);
            }
            YAMLMapper yaml = YAMLMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            return yaml.readValue(in, Catalog.class).models();
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "cannot read " + resource + ": " + e.getMessage(), e);
        }
    }

    /** Entry for a model id, or null when unknown. */
    public ModelInfo find(String modelId) {
        return byId.get(modelId);
    }

    /** All known models in catalog order. */
    public List<ModelInfo> all() {
        return List.copyOf(byId.values());
    }

    /** Default model id for a role, falling back to the worker default. */
    public String defaultFor(String role) {
        String id = role == null ? null : byRole.get(role);
        if (id == null) {
            id = byRole.get(DEFAULT_ROLE);
        }
        if (id == null) {
            throw new MobiusException(ErrorCode.INTERNAL, "no default model configured for role " + role);
        }
        return id;
    }

    /** Provider of a model; unknown ids are assumed to be Crusoe models. */
    public Provider providerOf(String modelId) {
        ModelInfo m = byId.get(modelId);
        return m == null ? Provider.CRUSOE : m.provider();
    }

    /** Cost in USD: (prompt - cached) * input + cached * cachedInput + completion * output, per 1M tokens. */
    public double cost(String modelId, TokenUsage usage) {
        ModelInfo m = byId.get(modelId);
        if (m == null) {
            if (warnedUnknown.add(modelId)) {
                log.warn("unknown model '{}' in price table; cost counted as 0", modelId);
            }
            return 0;
        }
        long uncached = Math.max(0, usage.promptTokens() - usage.cachedTokens());
        return (uncached * m.inputPerM() + usage.cachedTokens() * m.cachedInputPerM()
                + usage.completionTokens() * m.outputPerM()) / 1_000_000d;
    }
}
