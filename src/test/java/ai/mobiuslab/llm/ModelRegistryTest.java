package ai.mobiuslab.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Catalog loading and cost formula. */
class ModelRegistryTest {

    private final ModelRegistry registry = new ModelRegistry();

    @Test
    void loadsCatalogAndRoleDefaults() {
        assertEquals("nvidia/nemotron-3.5-lightning-30b-a3b", registry.defaultFor(null));
        assertEquals("nvidia/Nemotron-3-Super-120B-A12B", registry.defaultFor("master"));
        assertEquals("deepseek-ai/Deepseek-V4-Flash", registry.defaultFor("referee"));
        assertEquals("openai/gpt-oss-120b", registry.defaultFor("structured"));
        assertEquals(ModelRegistry.Provider.JEV, registry.providerOf("jev-latest"));
    }

    @Test
    void costUsesUncachedCachedAndCompletionPrices() {
        TokenUsage u = new TokenUsage(10_000, 4_000, 2_000);
        double expected = (6_000 * 0.30 + 4_000 * 0.15 + 2_000 * 2.40) / 1_000_000;
        assertEquals(expected, registry.cost("nvidia/Nemotron-3-Super-120B-A12B", u), 1e-12);
        assertEquals(0, registry.cost("unknown/model", u));
    }
}
