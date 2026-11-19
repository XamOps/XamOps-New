package com.xammer.cloud.service.matters;

import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The multi-value filter rule of the Endpoint drawer and the Files page (design 5.3), extracted from build
 * 2's {@code EndpointFilters.values}: every occurrence split on {@code ','}, values trimmed, empties
 * dropped, de-duplicated and sorted, 1..50 values, each checked by its {@link Rule}.
 *
 * <p>It gives only build 2's three messages, with the limit taken from the rule, plus the {@code HEX24}
 * shape of the code message. None repeats the rejected value.
 */
final class FilterValues {

    /** Most distinct values one filter may carry. */
    static final int MAX_VALUES = 50;

    /** Longest e-mail value (RFC 5321's path limit, rounded as design 5.3 gives it). */
    static final int MAX_EMAIL_LENGTH = 320;

    /** A sensitive-category or app-type code such as {@code PII} or {@code GOOGLE_WORKSPACE}. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private static final Pattern HEX24 = Pattern.compile("^[0-9a-fA-F]{24}$");

    /** The per-value rule: a length limit and, for codes and ids, a pattern checked after the length. */
    enum Rule {
        /** Upper-case codes of letters, digits and underscores, 1..128 characters. */
        CODE(EndpointFilters.MAX_VALUE_LENGTH),
        /** Any text, 1..128 characters. */
        TEXT(EndpointFilters.MAX_VALUE_LENGTH),
        /** An e-mail address, 1..320 characters (format only). */
        EMAIL(MAX_EMAIL_LENGTH),
        /** A 24-hex id, lower-cased. */
        HEX24(EndpointFilters.MAX_VALUE_LENGTH);

        private final int maxLength;

        Rule(int maxLength) {
            this.maxLength = maxLength;
        }
    }

    private FilterValues() {
    }

    /**
     * The sorted, de-duplicated values of {@code name}, or an empty list when the parameter is absent.
     * Other parameter names are ignored here: the controller's {@code requireAllowed} has checked them.
     *
     * @throws DspmBadRequestException naming the parameter, never the value
     */
    static List<String> parse(MultiValueMap<String, String> params, String name, Rule rule) {
        List<String> raw = params.get(name);
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> values = new TreeSet<>();
        for (String joined : raw) {
            if (joined == null) {
                continue;
            }
            for (String part : joined.split(",", -1)) {
                String value = part.trim();
                if (value.isEmpty()) {
                    continue;
                }
                values.add(check(name, value, rule));
                if (values.size() > MAX_VALUES) {
                    throw tooManyOrNone(name);
                }
            }
        }
        if (values.isEmpty()) {
            throw tooManyOrNone(name);
        }
        return List.copyOf(values);
    }

    /** One trimmed, non-empty value checked by {@code rule}; {@code HEX24} values come back lower-cased. */
    static String check(String name, String value, Rule rule) {
        if (value.length() > rule.maxLength || DatastoreParams.hasControlCharacter(value)) {
            throw new DspmBadRequestException(name,
                    name + " values must be 1 to " + rule.maxLength + " characters without control characters");
        }
        if (rule == Rule.CODE && !CODE.matcher(value).matches()) {
            throw new DspmBadRequestException(name,
                    name + " values must be upper-case codes of letters, digits and underscores");
        }
        if (rule == Rule.HEX24) {
            if (!HEX24.matcher(value).matches()) {
                throw new DspmBadRequestException(name, name + " values must be 24 hexadecimal characters");
            }
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private static DspmBadRequestException tooManyOrNone(String name) {
        return new DspmBadRequestException(name, name + " needs 1 to " + MAX_VALUES + " values");
    }
}
