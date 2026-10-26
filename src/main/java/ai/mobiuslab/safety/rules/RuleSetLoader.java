package ai.mobiuslab.safety.rules;

import ai.mobiuslab.common.ErrorCode;
import ai.mobiuslab.common.MobiusException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the built-in rule file and extracts rule overrides embedded in the policy markdown. */
public final class RuleSetLoader {

    /** Classpath location of the built-in rules. */
    public static final String DEFAULT_RULES = "/safety/default-rules.yml";

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();
    private static final Pattern OVERRIDE_BLOCK =
            Pattern.compile("```yaml\\s+safety-rules\\s*\\R(.*?)```", Pattern.DOTALL);

    private RuleSetLoader() {
    }

    /** Parses the built-in rule file from the classpath. */
    public static RuleSet loadDefault() {
        try (InputStream in = RuleSetLoader.class.getResourceAsStream(DEFAULT_RULES)) {
            if (in == null) {
                throw new MobiusException(ErrorCode.INTERNAL, "Missing classpath resource " + DEFAULT_RULES);
            }
            return YAML.readValue(in, RuleSet.class);
        } catch (IOException e) {
            throw new MobiusException(ErrorCode.INTERNAL, "Cannot parse " + DEFAULT_RULES, e);
        }
    }

    /** Parses a YAML rule set. */
    public static RuleSet parse(String yaml) {
        try {
            return YAML.readValue(yaml, RuleSet.class);
        } catch (IOException e) {
            throw MobiusException.invalid("Invalid safety-rules YAML: " + e.getMessage());
        }
    }

    /** Returns the override embedded in a policy document (```yaml safety-rules block), or null. */
    public static RuleSet overrideFromPolicy(String policyMarkdown) {
        if (policyMarkdown == null) {
            return null;
        }
        Matcher m = OVERRIDE_BLOCK.matcher(policyMarkdown);
        return m.find() ? parse(m.group(1)) : null;
    }

    /** Built-in rules overridden by the policy's embedded block. */
    public static RuleSet effective(RuleSet base, String policyMarkdown) {
        return base.overriddenBy(overrideFromPolicy(policyMarkdown));
    }
}
