package ai.mobiuslab.safety.rules;

import ai.mobiuslab.safety.ActionKind;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hard-rule configuration: command and secret patterns, protected path globs and allowed hosts.
 * Null lists in an override mean "keep the base list".
 */
public record RuleSet(List<PatternRule> commandRules, List<PatternRule> secretRules,
                      List<String> protectedPaths, List<String> allowedHosts) {

    public RuleSet {
        commandRules = commandRules == null ? null : List.copyOf(commandRules);
        secretRules = secretRules == null ? null : List.copyOf(secretRules);
        protectedPaths = protectedPaths == null ? null : List.copyOf(protectedPaths);
        allowedHosts = allowedHosts == null ? null : List.copyOf(allowedHosts);
    }

    /** Returns this set with every non-null list of {@code override} replacing the base list. */
    public RuleSet overriddenBy(RuleSet override) {
        if (override == null) {
            return this;
        }
        return new RuleSet(
                override.commandRules != null ? override.commandRules : commandRules,
                override.secretRules != null ? override.secretRules : secretRules,
                override.protectedPaths != null ? override.protectedPaths : protectedPaths,
                override.allowedHosts != null ? override.allowedHosts : allowedHosts);
    }

    /** A regex rule with an id, limited to some action kinds (empty = all). */
    public record PatternRule(String id, String pattern, Set<ActionKind> kinds, String reason) {

        public PatternRule {
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        }

        /** True when the rule applies to this action kind. */
        public boolean appliesTo(ActionKind kind) {
            return kinds.isEmpty() || kinds.contains(kind);
        }

        /** Compiled, case-insensitive pattern. */
        public Pattern compiled() {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        }
    }
}
