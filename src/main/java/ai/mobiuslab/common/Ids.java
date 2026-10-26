package ai.mobiuslab.common;

import java.security.SecureRandom;

/** Generates time-sortable, prefixed identifiers such as {@code agt_0K3Z9QF2M1XHB7RT4D}. */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** Returns a new id with the given short prefix; 10 time chars + 10 random chars (Crockford base32). */
    public static String newId(String prefix) {
        char[] out = new char[20];
        long time = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        for (int i = 10; i < 20; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return prefix + "_" + new String(out);
    }
}
