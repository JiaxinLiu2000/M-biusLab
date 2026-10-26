package ai.mobiuslab.runtime;

import ai.mobiuslab.common.MobiusException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Name-indexed registry of all {@link CodeFunction} beans (thread-safe; functions may be added at runtime). */
@Component
public class CodeFunctionRegistry {

    private final Map<String, CodeFunction> functions = new ConcurrentHashMap<>();

    public CodeFunctionRegistry(List<CodeFunction> beans) {
        beans.forEach(this::register);
    }

    /** Registers a function; duplicate names are rejected. */
    public void register(CodeFunction fn) {
        if (functions.putIfAbsent(fn.name(), fn) != null) {
            throw MobiusException.invalid("duplicate code function " + fn.name());
        }
    }

    /** Function by name; throws NOT_FOUND. */
    public CodeFunction get(String name) {
        CodeFunction fn = name == null ? null : functions.get(name);
        if (fn == null) {
            throw MobiusException.notFound("code function", name);
        }
        return fn;
    }

    /** Registered names. */
    public Set<String> names() {
        return Set.copyOf(functions.keySet());
    }
}
