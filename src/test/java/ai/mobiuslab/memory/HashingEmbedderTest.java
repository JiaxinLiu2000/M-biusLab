package ai.mobiuslab.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingEmbedderTest {

    private final HashingEmbedder e = new HashingEmbedder();

    @Test
    void deterministicAndNormalized() {
        float[] a = e.embed("Protein folding with transformers");
        assertThat(a).hasSize(384).containsExactly(new HashingEmbedder().embed("Protein folding with transformers"));
        assertThat(Embedder.cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void similarTextsScoreHigher() {
        float[] q = e.embed("protein folding prediction with deep learning");
        double similar = Embedder.cosine(q, e.embed("deep learning predicts protein folding"));
        double unrelated = Embedder.cosine(q, e.embed("recipe for chocolate cake and coffee"));
        assertThat(similar).isGreaterThan(unrelated + 0.2);
    }
}
