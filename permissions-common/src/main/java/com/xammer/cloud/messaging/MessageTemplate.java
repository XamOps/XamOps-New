package com.xammer.cloud.messaging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes positional variables into an SMS body.
 *
 * <p>Needed because {@code POST /v1/sms/countrycode} takes <em>both</em> {@code message} and
 * {@code template_id}: the provider matches the rendered text against the approved DLT template and
 * rejects the send if they diverge. So the body must be rendered locally from the same values, using
 * a pattern that mirrors the approved wording — hence the pattern lives in configuration.
 *
 * <p>Variables are a positional {@link List}, not a named map, because that is what both channels
 * actually are. DLT templates spell every placeholder identically — an approved template on this
 * account reads {@code "Dear {#var#}, Scale securely with Xammer {#var#} ... Connect: {#var#}"} —
 * and WhatsApp takes {@code sample: {"bodyvar": [...]}}, an ordered array. Naming the values would
 * imply a lookup that neither API performs, and invites a silent reordering.
 *
 * <p>Recognised placeholder styles:
 * <ul>
 *   <li>{@code {#var#}} — the real DLT form; each occurrence consumes the next value</li>
 *   <li>{@code {#var1#}} — explicitly numbered, 1-based</li>
 *   <li>{@code {{var1}}} / {@code {{1}}} — plainer forms some tooling emits</li>
 * </ul>
 */
public final class MessageTemplate {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{#\\s*(\\w+)\\s*#}|\\{\\{\\s*(\\w+)\\s*}}");

    /** Captures the trailing digits of "var3" / "3", or nothing for a bare "var". */
    private static final Pattern INDEX = Pattern.compile("(?i)^(?:var)?(\\d*)$");

    private MessageTemplate() {
    }

    /**
     * Replaces every placeholder with the corresponding value.
     *
     * <p>A placeholder with no value is replaced with an empty string rather than left in place: a
     * literal {@code {#var#}} arriving on a handset is worse than a terse sentence, and it would
     * also fail the provider's template match.
     *
     * @param pattern body pattern, e.g. {@code "XamOps alert for {#var#}: {#var#} critical"}
     * @param values  positional values, first element filling the first placeholder
     * @return rendered text, or an empty string when {@code pattern} is null or blank
     */
    public static String render(String pattern, List<String> values) {
        if (pattern == null || pattern.isBlank()) {
            return "";
        }
        List<String> vars = values == null ? List.of() : values;

        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuilder out = new StringBuilder();
        int cursor = 0;

        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);

            int index;
            Matcher indexMatch = INDEX.matcher(token);
            if (indexMatch.matches() && !indexMatch.group(1).isEmpty()) {
                // Explicitly numbered and 1-based, so "var2" is the second value.
                index = Integer.parseInt(indexMatch.group(1)) - 1;
            } else {
                // Bare "{#var#}" carries no index — its position in the pattern is its index.
                index = cursor++;
            }

            String value = (index >= 0 && index < vars.size() && vars.get(index) != null)
                    ? vars.get(index)
                    : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }
}
