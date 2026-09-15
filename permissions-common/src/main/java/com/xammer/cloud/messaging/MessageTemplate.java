package com.xammer.cloud.messaging;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes template variables into an SMS body.
 *
 * <p>Needed because {@code POST /v1/sms/countrycode} takes <em>both</em> {@code message} and
 * {@code template_id}: the provider matches the rendered text against the approved DLT template
 * and rejects the send if they diverge. So the body must be rendered locally from the same
 * variables, using a pattern that mirrors whatever text DLT approved — hence the pattern lives
 * in configuration rather than in code.
 *
 * <p>Accepts the DLT convention {@code {#var1#}} and the plainer {@code {{var1}}}, since which
 * one appears depends on how the template was registered.
 */
public final class MessageTemplate {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{#\\s*(\\w+)\\s*#}|\\{\\{\\s*(\\w+)\\s*}}");

    private MessageTemplate() {
    }

    /**
     * Replaces every placeholder with its value from {@code variables}.
     *
     * <p>An unknown placeholder is replaced with an empty string rather than left in place: a
     * literal {@code {#var4#}} arriving on a customer's phone is worse than a slightly terse
     * sentence, and it would also fail the provider's template match.
     *
     * @param pattern   body pattern, e.g. {@code "XamOps: {#var1#} alerts on {#var2#}"}
     * @param variables placeholder name to value
     * @return rendered text, or an empty string when {@code pattern} is null or blank
     */
    public static String render(String pattern, Map<String, String> variables) {
        if (pattern == null || pattern.isBlank()) {
            return "";
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;

        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String value = vars.getOrDefault(name, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }
}
