package ai.mobiuslab.safety.review;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed (non-evolvable) safety prompts loaded from {@code classpath:safety/prompts/*.md}. */
public final class SafetyPrompts {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SafetyPrompts() {
    }

    /** Returns the prompt text for a name such as "review-system". */
    public static String get(String name) {
        return CACHE.computeIfAbsent(name, SafetyPrompts::load);
    }

    private static String load(String name) {
        String path = "/safety/prompts/" + name + ".md";
        try (InputStream in = SafetyPrompts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new MobiusException(ErrorCode.INTERNAL, "Missing safety prompt " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot read safety prompt " + path, e);
        }
    }
}
