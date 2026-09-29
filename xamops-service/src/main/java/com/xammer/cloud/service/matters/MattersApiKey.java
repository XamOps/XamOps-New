package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Optional;

/**
 * A parsed Matters API key: {@code mts_<env>_<key_id>_<secret>}.
 *
 * Exists so the three non-secret parts of a key can be used freely — stored in columns, shown in the
 * admin UI, written to the audit trail — while the secret stays in one place and is never logged or
 * serialised. {@link #toString()} is overridden for exactly that reason: a record's generated
 * {@code toString} prints every component, so the default would put a live credential into any log
 * line that interpolated one of these.
 *
 * @param env    environment segment, e.g. {@code prod}. Binds the key to one Matters environment —
 *               a key issued for one is rejected against another with an explicit
 *               "API key is for a different environment", so this is checked against the mapping's
 *               base URL before a call is attempted.
 * @param keyId  key-id segment. Identifies the key in Matters' console for rotation and revocation,
 *               and is the only key-derived value written to the audit trail.
 * @param secret the secret segment. Never store, never log.
 */
public record MattersApiKey(String env, String keyId, String secret) {

    private static final String PREFIX = "mts_";

    /** Four underscore-separated parts: the literal prefix, env, key id, secret. */
    private static final int PART_COUNT = 4;

    /**
     * Parses a key, or returns empty when it is not a Matters key.
     *
     * Empty rather than throwing because the two callers want different things from a bad key: the
     * admin save path rejects it with a validation message, while the read path raises
     * {@link MattersApiException.Reason#MALFORMED_KEY}. Neither wants an exception thrown from a
     * parser.
     *
     * The secret itself may contain characters that are not underscores but nothing here assumes a
     * character set for it — {@code splitn(4)} keeps everything after the third underscore intact,
     * so a secret containing an underscore is not silently truncated.
     */
    public static Optional<MattersApiKey> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim();
        if (key.isEmpty() || !key.startsWith(PREFIX)) {
            return Optional.empty();
        }
        // limit = 4 so only the first three underscores split; the remainder is the secret verbatim.
        String[] parts = key.split("_", PART_COUNT);
        if (parts.length != PART_COUNT) {
            return Optional.empty();
        }
        String env = parts[1];
        String keyId = parts[2];
        String secret = parts[3];
        if (env.isEmpty() || keyId.isEmpty() || secret.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MattersApiKey(env, keyId, secret));
    }

    /** True when {@code raw} is shaped like a Matters key. */
    public static boolean isWellFormed(String raw) {
        return parse(raw).isPresent();
    }

    /**
     * Last four characters of the secret, for the admin UI and as the
     * {@code api_key_last_four} presence sentinel.
     *
     * Four characters of a 43-character secret is not enough to narrow a guess usefully, and it is
     * what lets an operator confirm they pasted the key they meant to without the key being
     * retrievable.
     */
    public String lastFour() {
        return secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
    }

    /**
     * True when this key's environment is consistent with {@code baseUrl}.
     *
     * The mistake this catches: pasting a key from one Matters region against another region's host.
     * Matters answers that with a distinct 401, and the fix is to correct the URL rather than rotate
     * the key — so it is worth catching at save time, when the operator who pasted it is still
     * looking at the form.
     *
     * Deliberately permissive. Matters' regional hosts are {@code app.<region>.matters.ai} while the
     * key's segment is an <em>environment</em> ({@code prod}), and those two vocabularies do not
     * share values — so the only mismatch that can be asserted is a key naming an environment that
     * contradicts one named in the host, e.g. a {@code staging} key against a host containing
     * {@code prod}. Anything unrecognised passes, because a false rejection here blocks a working
     * configuration and the live call is the real check.
     */
    public boolean matchesBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true;
        }
        String host = baseUrl.toLowerCase(Locale.ROOT);
        String keyEnv = env.toLowerCase(Locale.ROOT);
        for (String known : new String[]{"prod", "production", "staging", "stage", "dev", "uat", "test"}) {
            if (host.contains("." + known + ".") || host.contains("-" + known + ".")) {
                return keyEnv.startsWith(known) || known.startsWith(keyEnv);
            }
        }
        return true;
    }

    /**
     * Redacted. Never prints the secret.
     *
     * Overriding this is the point of the class: the compiler-generated record {@code toString}
     * includes every component, so one {@code log.debug("key={}", key)} would leak a live credential
     * into the log stream.
     */
    @Override
    public String toString() {
        return "MattersApiKey[env=" + env + ", keyId=" + keyId + ", secret=****" + lastFour() + "]";
    }
}
