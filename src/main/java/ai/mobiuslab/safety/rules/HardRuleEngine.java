package ai.mobiuslab.safety.rules;

import ai.mobiuslab.safety.ActionKind;
import ai.mobiuslab.safety.ActionProposal;
import ai.mobiuslab.safety.SafetyVerdict.Decision;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, model-free safety rules. Thread-safe: compiled patterns are cached in a concurrent map.
 */
public final class HardRuleEngine {

    /** Kinds whose target is a file path. */
    private static final Set<ActionKind> PATH_KINDS =
            EnumSet.of(ActionKind.FILE_WRITE, ActionKind.FILE_DELETE, ActionKind.FRONTEND_CHANGE);
    private static final Pattern DOT_DOT = Pattern.compile("(^|[/\\\\])\\.\\.([/\\\\]|$)");
    private static final Pattern WIN_ABS = Pattern.compile("^[A-Za-z]:[/\\\\].*|^\\\\\\\\.*");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9+/_\\-]{40,}={0,2}");
    /** Platform ids ({@code common.Ids} format) plus optional slug prefixes/suffixes; never credentials. */
    private static final Pattern PLATFORM_ID = Pattern.compile("(?:[a-z0-9]+-)*\\b[a-z]{2,8}_[0-9A-HJKMNP-TV-Z]{20}\\b"
            + "(?:-(?:[a-z]{2,8}_[0-9A-HJKMNP-TV-Z]{20}\\b|[a-z0-9]+))*");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://([^/\\s:'\"]+)", Pattern.CASE_INSENSITIVE);

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();
    private final Map<String, Pattern> globCache = new ConcurrentHashMap<>();

    /** Runtime switches the engine honours. */
    public record Options(Path home, boolean allowNetwork, boolean requireUserForAgentDelete) {
    }

    /**
     * Outcome of the hard rules.
     *
     * @param decision DENY or ESCALATE when a rule fired decisively, null when the proposal passes
     */
    public record Outcome(Decision decision, List<String> ruleHits, List<String> reasons) {
        /** True when no decisive rule fired. */
        public boolean passed() {
            return decision == null;
        }
    }

    /** Evaluates every hard rule; any DENY hit wins over ESCALATE hits. */
    public Outcome evaluate(ActionProposal p, RuleSet rules, Options opts) {
        List<String> denyHits = new ArrayList<>();
        List<String> escalateHits = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        String content = p.content() == null ? "" : p.content();
        String target = p.target() == null ? "" : p.target();

        for (RuleSet.PatternRule r : nullSafe(rules.commandRules())) {
            if (r.appliesTo(p.kind()) && pattern(r).matcher(content).find()) {
                denyHits.add(r.id());
                reasons.add(r.reason() + " [" + r.id() + "]");
            }
        }
        String secretText = content + "\n" + target + "\n" + (p.description() == null ? "" : p.description());
        for (RuleSet.PatternRule r : nullSafe(rules.secretRules())) {
            if (r.appliesTo(p.kind()) && pattern(r).matcher(secretText).find()) {
                denyHits.add(r.id());
                reasons.add(r.reason() + " [" + r.id() + "]");
            }
        }
        if (containsHighEntropyToken(secretText)) {
            denyHits.add("secret.long-token");
            reasons.add("Long base64-like token looks like a credential [secret.long-token]");
        }
        if (PATH_KINDS.contains(p.kind())) {
            checkPath(target, rules, opts.home(), denyHits, reasons);
        }
        if (p.kind() == ActionKind.SHELL_EXEC || p.kind() == ActionKind.CODE_EXEC) {
            for (String glob : nullSafe(rules.protectedPaths())) {
                String literal = literalPrefix(glob);
                if (literal.length() >= 4 && content.replace('\\', '/').toLowerCase(Locale.ROOT).contains(literal)) {
                    denyHits.add("path.protected");
                    reasons.add("Command references protected path " + glob + " [path.protected]");
                    break;
                }
            }
        }
        if (p.kind() == ActionKind.NETWORK && !opts.allowNetwork()) {
            String host = hostOf(target);
            if (host == null || !allowed(host, rules.allowedHosts())) {
                denyHits.add("network.unauthorized");
                reasons.add("Host not allowlisted: " + host + " [network.unauthorized]");
            }
        }
        if ((p.kind() == ActionKind.SHELL_EXEC || p.kind() == ActionKind.CODE_EXEC) && !opts.allowNetwork()) {
            Matcher m = URL_IN_TEXT.matcher(content);
            while (m.find()) {
                if (!allowed(m.group(1).toLowerCase(Locale.ROOT), rules.allowedHosts())) {
                    denyHits.add("network.unauthorized");
                    reasons.add("Command contacts non-allowlisted host " + m.group(1) + " [network.unauthorized]");
                    break;
                }
            }
        }
        if (p.kind() == ActionKind.FRONTEND_CHANGE) {
            String shot = p.attachments().get("screenshotPath");
            if (shot == null || shot.isBlank()) {
                denyHits.add("frontend.no-screenshot");
                reasons.add("Frontend change without attachments.screenshotPath [frontend.no-screenshot]");
            }
        }
        if (p.kind() == ActionKind.CRITERIA_CHANGE) {
            escalateHits.add("criteria.change");
            reasons.add("Evaluation criteria changes always need a user decision [criteria.change]");
        }
        if (p.kind() == ActionKind.AGENT_DELETE && opts.requireUserForAgentDelete()) {
            escalateHits.add("agent.delete.user-required");
            reasons.add("Agent deletion requires the user [agent.delete.user-required]");
        }
        List<String> hits = new ArrayList<>(denyHits);
        hits.addAll(escalateHits);
        Decision d = !denyHits.isEmpty() ? Decision.DENY : !escalateHits.isEmpty() ? Decision.ESCALATE : null;
        return new Outcome(d, List.copyOf(hits), List.copyOf(reasons));
    }

    private void checkPath(String target, RuleSet rules, Path home, List<String> hits, List<String> reasons) {
        if (target.isBlank()) {
            hits.add("path.missing");
            reasons.add("File action without a target path [path.missing]");
            return;
        }
        if (DOT_DOT.matcher(target).find()) {
            hits.add("path.escape.dotdot");
            reasons.add("Path contains '..' segment: " + target + " [path.escape.dotdot]");
            return;
        }
        Path resolved;
        try {
            boolean absolute = target.startsWith("/") || target.startsWith("\\") || WIN_ABS.matcher(target).matches()
                    || target.startsWith("~");
            if (absolute) {
                Path p = Path.of(target.startsWith("~") ? "/" + target : target).toAbsolutePath().normalize();
                if (!p.startsWith(home)) {
                    hits.add("path.escape.outside-home");
                    reasons.add("Absolute path outside MOBIUS_HOME: " + target + " [path.escape.outside-home]");
                    return;
                }
                resolved = p;
            } else {
                resolved = home.resolve(target).normalize();
            }
        } catch (InvalidPathException e) {
            hits.add("path.invalid");
            reasons.add("Invalid path " + target + " [path.invalid]");
            return;
        }
        Path real = realPathOfExistingAncestor(resolved);
        if (real != null && !real.startsWith(realOrSelf(home))) {
            hits.add("path.escape.symlink");
            reasons.add("Path resolves outside MOBIUS_HOME via a link: " + target + " [path.escape.symlink]");
            return;
        }
        String rel = home.relativize(resolved).toString().replace('\\', '/');
        String raw = target.replace('\\', '/');
        for (String glob : nullSafe(rules.protectedPaths())) {
            Pattern g = glob(glob);
            if (g.matcher(rel).matches() || g.matcher(raw).matches()) {
                hits.add("path.protected");
                reasons.add("Protected path " + glob + ": " + target + " [path.protected]");
                return;
            }
        }
    }

    private static Path realPathOfExistingAncestor(Path p) {
        Path cur = p;
        while (cur != null && !Files.exists(cur, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            cur = cur.getParent();
        }
        if (cur == null) {
            return null;
        }
        try {
            return cur.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static Path realOrSelf(Path p) {
        try {
            return Files.exists(p) ? p.toRealPath() : p;
        } catch (IOException e) {
            return p;
        }
    }

    private Pattern pattern(RuleSet.PatternRule r) {
        return patternCache.computeIfAbsent(r.pattern(), k -> r.compiled());
    }

    private Pattern glob(String glob) {
        return globCache.computeIfAbsent(glob, HardRuleEngine::globToRegex);
    }

    /** Converts a glob ("**" any depth, "*" one segment) to a regex that also matches at any sub-directory. */
    static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("(?:.*/)?");
        String g = glob.startsWith("**/") ? glob.substring(3) : glob;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                sb.append(".*");
                i++;
                if (i + 1 < g.length() && g.charAt(i + 1) == '/') {
                    i++;
                    sb.append("/?");
                }
            } else if (c == '*') {
                sb.append("[^/]*");
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String literalPrefix(String glob) {
        String g = glob.startsWith("**/") ? glob.substring(3) : glob;
        int star = g.indexOf('*');
        return (star < 0 ? g : g.substring(0, star)).toLowerCase(Locale.ROOT);
    }

    private static boolean containsHighEntropyToken(String text) {
        Matcher m = LONG_TOKEN.matcher(PLATFORM_ID.matcher(text).replaceAll(" "));
        while (m.find()) {
            String t = m.group();
            boolean upper = false, lower = false, digit = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                upper |= Character.isUpperCase(c);
                lower |= Character.isLowerCase(c);
                digit |= Character.isDigit(c);
            }
            if (upper && lower && digit) {
                return true;
            }
        }
        return false;
    }

    static String hostOf(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        try {
            String t = target.contains("://") ? target : "http://" + target;
            String host = URI.create(t.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean allowed(String host, List<String> allow) {
        for (String a : nullSafe(allow)) {
            String h = a.toLowerCase(Locale.ROOT);
            if (host.equals(h) || host.endsWith("." + h)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> nullSafe(List<T> l) {
        return l == null ? List.of() : l;
    }
}
