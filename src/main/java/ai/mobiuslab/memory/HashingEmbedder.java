package ai.mobiuslab.memory;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Deterministic, dependency-free embedder: lowercase word unigrams plus character trigrams hashed
 * (FNV-1a 32) into signed buckets, then L2-normalized. Stateless and therefore thread-safe.
 */
@Component
public class HashingEmbedder implements Embedder {

    /** Default dimension. */
    public static final int DIMENSION = 384;

    /** {@inheritDoc} */
    @Override
    public int dimension() {
        return DIMENSION;
    }

    /** {@inheritDoc} */
    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMENSION];
        if (text == null || text.isBlank()) {
            return v;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lower.split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            add(v, "w:" + word, 2.0f);
            String padded = " " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(v, "c:" + padded.substring(i, i + 3), 1.0f);
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private static void add(float[] v, String feature, float weight) {
        int h = fnv1a(feature);
        int bucket = Math.floorMod(h, DIMENSION);
        float sign = ((h >>> 31) & 1) == 0 ? 1f : -1f;
        v[bucket] += sign * weight;
    }

    private static int fnv1a(String s) {
        int hash = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }
}
