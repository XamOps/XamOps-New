package com.xammer.cloud.service.autospotting.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Strips credentials, keys and tokens before anything reaches an {@code autospotting_audit} row, a
 * step's {@code input}/{@code output}, {@code last_error_message} or a log line (design-core.md §8,
 * FR-SEC-06, AC-SEC-07). Two entry points:
 *
 * <ul>
 *   <li>{@link #redactJson(String)} walks a JSON document and masks any field whose name looks
 *       sensitive (access key id, secret, session token, private key, OAuth/bearer token,
 *       {@code Authorization} header), then masks sensitive <em>values</em> anywhere in the tree so
 *       a secret pasted into a free-text field is caught too.</li>
 *   <li>{@link #redactMessage(String)} masks the same value shapes in a plain string (a provider
 *       error body or a log line).</li>
 * </ul>
 *
 * <p>The value patterns match the shapes the plan's fixtures use: an {@code AKIA}/{@code ASIA}-style
 * 20-char access key id, a 40-char base64-ish secret access key, a PEM private-key block, and a
 * bearer token. Matching is deliberately broad and masks on suspicion: an audit trail with a masked
 * non-secret is safe; one with a leaked secret is not.
 */
@Component
public class AuditRedactor {

    private static final Logger log = LoggerFactory.getLogger(AuditRedactor.class);

    public static final String MASK = "***REDACTED***";

    private final ObjectMapper objectMapper;

    /** Field names (case-insensitive substring) whose value is always masked. */
    private static final List<String> SENSITIVE_FIELD_SUBSTRINGS = List.of(
            "accesskey", "access-key", "access_key",
            "secretkey", "secret-key", "secret_key", "secretaccesskey",
            "sessiontoken", "session-token", "session_token",
            "privatekey", "private-key", "private_key",
            "password", "passwd",
            "credential", "credentials",
            "authorization",
            "token",
            "secret",
            "apikey", "api-key", "api_key",
            "clientsecret", "client-secret", "client_secret",
            "refreshtoken", "refresh-token", "refresh_token",
            "idtoken", "id-token", "id_token");

    /** Value shapes masked wherever they appear (free text, log lines, non-sensitive field values). */
    private static final List<Pattern> SENSITIVE_VALUE_PATTERNS = List.of(
            // AWS access key id: AKIA/ASIA/AGPA/AIDA... + 16 uppercase alnum.
            Pattern.compile("\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ABIA|ACCA)[A-Z0-9]{16}\\b"),
            // PEM private key block (RSA / EC / OPENSSH / generic).
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            // Bearer token in an Authorization value.
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-]+"),
            // Google OAuth access token.
            Pattern.compile("\\bya29\\.[A-Za-z0-9._\\-]+"),
            // 40-char base64-ish secret access key (AWS secret shape).
            Pattern.compile("\\b[A-Za-z0-9/+=]{40}\\b"));

    public AuditRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Redact a JSON document. Returns {@code null} for a null input. A document that doesn't parse as
     * JSON is treated as a plain message and run through {@link #redactMessage(String)}, so a raw
     * provider error body is still scrubbed.
     */
    public String redactJson(String json) {
        if (json == null) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return redactMessage(json);
        }
        redactNode(root);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Could not re-serialize redacted JSON; masking whole document");
            return MASK;
        }
    }

    /** Mask sensitive value shapes in a plain string (provider error body, log line). */
    public String redactMessage(String message) {
        if (message == null) {
            return null;
        }
        String out = message;
        for (Pattern p : SENSITIVE_VALUE_PATTERNS) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    private void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.fieldNames().forEachRemaining(name -> {
                JsonNode value = object.get(name);
                if (isSensitiveFieldName(name)) {
                    object.put(name, MASK);
                } else if (value != null && value.isValueNode() && value.isTextual()) {
                    object.put(name, redactMessage(value.asText()));
                } else {
                    redactNode(value);
                }
            });
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value != null && value.isTextual()) {
                    array.set(i, array.textNode(redactMessage(value.asText())));
                } else {
                    redactNode(value);
                }
            }
        }
    }

    private static boolean isSensitiveFieldName(String fieldName) {
        String lower = fieldName.toLowerCase();
        for (String s : SENSITIVE_FIELD_SUBSTRINGS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
