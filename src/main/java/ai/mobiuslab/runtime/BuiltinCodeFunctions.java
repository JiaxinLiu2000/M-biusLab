package ai.mobiuslab.runtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Built-in CODE node functions: text.template, json.pick, collect.merge, score.threshold. */
@Configuration(proxyBeanMethods = false)
public class BuiltinCodeFunctions {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /** Simple function implementation backed by a lambda. */
    public record Fn(String name, java.util.function.BiFunction<Map<String, Object>, Map<String, Object>, Map<String, Object>> body)
            implements CodeFunction {
        /** {@inheritDoc} */
        @Override
        public Map<String, Object> apply(Map<String, Object> in, Map<String, Object> config) {
            return body.apply(in, config);
        }
    }

    /** All built-ins (used by tests without Spring). */
    public static List<CodeFunction> all() {
        return List.of(textTemplate(), jsonPick(), collectMerge(), scoreThreshold());
    }

    /** text.template: config.template with {{var}} placeholders from input -> {outputKey|text}. */
    @Bean
    public static CodeFunction textTemplate() {
        return new Fn("text.template", (in, cfg) -> {
            String template = String.valueOf(cfg.getOrDefault("template", ""));
            Matcher m = VAR.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                Object v = lookup(in, m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
            }
            m.appendTail(sb);
            return Map.of(String.valueOf(cfg.getOrDefault("outputKey", "text")), sb.toString());
        });
    }

    /** json.pick: keeps config.keys (dotted paths allowed) from input. */
    @Bean
    public static CodeFunction jsonPick() {
        return new Fn("json.pick", (in, cfg) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Object keys = cfg.get("keys");
            if (keys instanceof List<?> list) {
                for (Object k : list) {
                    String key = String.valueOf(k);
                    Object v = lookup(in, key);
                    if (v != null) {
                        out.put(key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key, v);
                    }
                }
            }
            return out;
        });
    }

    /** collect.merge: input merged with config.values (config wins); lists under config.appendKey are concatenated. */
    @Bean
    public static CodeFunction collectMerge() {
        return new Fn("collect.merge", (in, cfg) -> {
            Map<String, Object> out = new LinkedHashMap<>(in);
            if (cfg.get("values") instanceof Map<?, ?> values) {
                values.forEach((k, v) -> out.put(String.valueOf(k), v));
            }
            if (cfg.get("appendKey") instanceof String key && in.get(key) instanceof List<?> a
                    && cfg.get("append") instanceof List<?> b) {
                List<Object> merged = new ArrayList<>(a);
                merged.addAll(b);
                out.put(key, merged);
            }
            return out;
        });
    }

    /** score.threshold: {pass: input[config.key] >= config.threshold, score}. */
    @Bean
    public static CodeFunction scoreThreshold() {
        return new Fn("score.threshold", (in, cfg) -> {
            String key = String.valueOf(cfg.getOrDefault("key", "score"));
            double threshold = ((Number) cfg.getOrDefault("threshold", 0.5)).doubleValue();
            Object v = lookup(in, key);
            double score = v instanceof Number n ? n.doubleValue() : v == null ? 0 : Double.parseDouble(String.valueOf(v));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pass", score >= threshold);
            out.put("score", score);
            return out;
        });
    }

    static Object lookup(Map<String, Object> in, String path) {
        Object cur = in;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        return cur;
    }
}
