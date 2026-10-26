package ai.mobiuslab.prompt;

import ai.mobiuslab.common.MobiusException;
import ai.mobiuslab.llm.ChatMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, thread-safe prompt rendering: volatility ordering, {{var}} substitution, same-role merging. */
public final class PromptRenderer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private PromptRenderer() {
    }

    /** Renders segments into chat messages; missing variables throw MobiusException(VALIDATION). */
    public static List<ChatMessage> render(String key, List<PromptSegment> segments, Map<String, Object> variables) {
        List<PromptSegment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparingInt(PromptSegment::volatility)); // List.sort is stable
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        List<ChatMessage> out = new ArrayList<>();
        ChatMessage.Role role = null;
        StringBuilder buf = new StringBuilder();
        for (PromptSegment seg : ordered) {
            String text = substitute(key, seg.text(), vars);
            if (seg.role() == role) {
                buf.append("\n\n").append(text);
            } else {
                if (role != null) {
                    out.add(new ChatMessage(role, buf.toString()));
                }
                role = seg.role();
                buf.setLength(0);
                buf.append(text);
            }
        }
        if (role != null) {
            out.add(new ChatMessage(role, buf.toString()));
        }
        return out;
    }

    /** Declared variable names in first-appearance order. */
    public static List<String> variables(List<PromptSegment> segments) {
        Set<String> names = new LinkedHashSet<>();
        for (PromptSegment s : segments) {
            Matcher m = VAR.matcher(s.text());
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return List.copyOf(names);
    }

    private static String substitute(String key, String text, Map<String, Object> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 64);
        while (m.find()) {
            String name = m.group(1);
            if (!vars.containsKey(name) || vars.get(name) == null) {
                throw MobiusException.invalid("prompt '" + key + "' is missing variable '" + name + "'");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(vars.get(name))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
