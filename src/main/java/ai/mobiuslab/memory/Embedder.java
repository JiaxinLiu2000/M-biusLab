package ai.mobiuslab.memory;

/** Turns text into a fixed-size dense vector for similarity search. Implementations must be thread-safe. */
public interface Embedder {

    /** Vector dimension. */
    int dimension();

    /** Embeds text into an L2-normalized vector of {@link #dimension()} floats. */
    float[] embed(String text);

    /** Cosine similarity of two normalized vectors. */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
