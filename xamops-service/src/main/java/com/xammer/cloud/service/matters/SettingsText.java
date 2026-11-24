package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The text reader of every settings route (design 5.6.4): {@link DrawerJson#stringValue}, minus any value
 * shaped like a credential. A second line behind {@link ScanSettingsMapper}'s field whitelists.
 *
 * <p>Deliberately not a "long opaque string" test: the Assists {@code client_id} (44 to 56 base64
 * characters) is an identifier the console shows and must pass.
 */
final class SettingsText {

    private static final Logger log = LoggerFactory.getLogger(SettingsText.class);

    private static final List<Pattern> CREDENTIAL_SHAPES = List.of(
            // a URI with user info
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/@\\s]+@"),
            // a JWT
            Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\."),
            // a PEM block
            Pattern.compile("-----BEGIN "),
            // an AWS access key id
            Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"),
            // a key-value pair
            Pattern.compile("(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)\\s*[=:]",
                    Pattern.CASE_INSENSITIVE));

    private SettingsText() {
    }

    /**
     * {@code DrawerJson.stringValue(node)}, or null when the text is credential-shaped; a drop logs WARN
     * with the field name only, never the value.
     */
    static String clean(String field, JsonNode node) {
        String text = DrawerJson.stringValue(node);
        if (text != null && credentialShaped(text)) {
            logDrop(field);
            return null;
        }
        return text;
    }

    /** Logs a dropped value at WARN with the field name only (also used by the mapper's host rule). */
    static void logDrop(String field) {
        log.warn("dropped a credential-shaped value in {}", field);
    }

    /**
     * The cleaned text of each element of an array, nulls and drops skipped, at most {@code cap} values,
     * each at most {@code maxLength} characters (longer values are dropped); empty for a non-array.
     */
    static List<String> cleanAll(String field, JsonNode array, int cap, int maxLength) {
        List<String> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= cap) {
                break;
            }
            String value = clean(field, element);
            if (value != null && value.length() <= maxLength) {
                out.add(value);
            }
        }
        return out;
    }

    /** True for a URI with user info, a JWT, a PEM block, an AWS access key id or a secret key-value pair. */
    static boolean credentialShaped(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern shape : CREDENTIAL_SHAPES) {
            if (shape.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
