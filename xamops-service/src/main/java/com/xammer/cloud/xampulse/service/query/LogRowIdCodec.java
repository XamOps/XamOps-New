package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.exception.XamPulseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque log row ids: {@code base64url("<tenantId>|<tsMillis>|<instanceId>|<bodyHash>")}. The id is a locator, not
 * a capability: every use re-checks that the tenant is one the caller is authorised for.
 */
public final class LogRowIdCodec {

    public record RowRef(long tenantId, long tsMs, String instanceId, String bodyHash) {}

    private static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9._:\\-]{0,64}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,20}$");

    private LogRowIdCodec() {}

    public static String encode(long tenantId, long tsMs, String instanceId, String bodyHash) {
        String inst = instanceId == null ? "" : instanceId.replace("|", "");
        String raw = tenantId + "|" + tsMs + "|" + inst + "|" + bodyHash;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Any malformed or tampered id is the same uniform 404. */
    public static RowRef decode(String id) {
        try {
            if (id == null || id.length() > 300) {
                throw XamPulseException.notFound();
            }
            String raw = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            String[] p = raw.split("\\|", -1);
            if (p.length != 4 || !DIGITS.matcher(p[0]).matches() || !DIGITS.matcher(p[1]).matches()
                    || !INSTANCE.matcher(p[2]).matches() || !DIGITS.matcher(p[3]).matches()) {
                throw XamPulseException.notFound();
            }
            return new RowRef(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3]);
        } catch (IllegalArgumentException e) {
            throw XamPulseException.notFound();
        }
    }
}
