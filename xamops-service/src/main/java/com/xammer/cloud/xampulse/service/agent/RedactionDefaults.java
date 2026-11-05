package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.dto.agent.AgentConfig;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Default redaction rules, applied on the host before data leaves it. The patterns are written in the subset shared
 * by Java regex and Go RE2 (no look-around, no back-references) because the agent compiles them with Go. The same
 * patterns, with positive and negative samples, are in xamops-agent/internal/xampulse/testdata/redaction-cases.json
 * and both test suites run them (RedactionDefaultsTest here, config_render_test.go there).
 */
public final class RedactionDefaults {

    public static final List<AgentConfig.RedactionRule> RULES = List.of(
            new AgentConfig.RedactionRule("card-number", "\\b\\d(?:[ -]?\\d){12,15}\\b", "[REDACTED_CARD]"),
            new AgentConfig.RedactionRule("aws-access-key", "\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", "[REDACTED_AWS_KEY]"),
            new AgentConfig.RedactionRule("jwt", "\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b", "[REDACTED_JWT]"),
            new AgentConfig.RedactionRule("bearer-token", "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}", "Bearer [REDACTED]"),
            new AgentConfig.RedactionRule("email", "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", "[REDACTED_EMAIL]"),
            new AgentConfig.RedactionRule("secret-key-value",
                    "(?i)\\b(password|passwd|secret|token|api[_-]?key)\\s*[=:]\\s*[^\\s,;&\"']+", "$1=[REDACTED]"));

    private static final Pattern NOT_RE2 = Pattern.compile("\\(\\?[=!<>]|\\\\[1-9]|\\\\k<|\\*\\+|\\+\\+|\\?\\+|\\(\\?>");

    private RedactionDefaults() {}

    /**
     * A custom pattern must compile in Java and avoid the constructs Go's RE2 does not support, otherwise the
     * agent would reject the whole configuration.
     */
    public static String validateCustomPattern(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 500) {
            return "Redaction pattern must be 1 to 500 characters.";
        }
        if (NOT_RE2.matcher(pattern).find()) {
            return "Redaction pattern uses look-around, back-references or possessive quantifiers, which are not supported.";
        }
        try {
            Pattern.compile(pattern);
        } catch (RuntimeException e) {
            return "Redaction pattern is not a valid regular expression.";
        }
        return null;
    }
}
