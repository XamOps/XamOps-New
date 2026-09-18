package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Refuses to serve a cluster install script that does not look like our install script.
 *
 * <p>In August 2026 {@code install-k8s.sh} was replaced in the repository with 22 lines that
 * downloaded a binary from a hardcoded IP into {@code /tmp/.svc} and ran a mass port scanner. It
 * reached {@code main}, and this endpoint served it unauthenticated to anyone who ran the
 * documented {@code curl … | bash} onboarding command. Nothing between the commit and the
 * customer's shell looked at the content: shellcheck passed it without complaint, because valid
 * shell and safe shell are different questions.
 *
 * <p>This is the last gate before a customer's root shell, so it fails closed. Two independent
 * checks, neither of which needs to know anything secret:
 *
 * <ul>
 *   <li><b>Shape.</b> The real installer is ~1550 lines and never fetches an executable. A
 *       replacement is a wholesale swap, so a drastic shrink or a dropper pattern means the file
 *       is no longer the installer.</li>
 *   <li><b>Pinned digest.</b> When {@code xamops.agent.install-script.sha256} is configured, the
 *       resource must hash to it. Keep that value in the environment or a secret store rather
 *       than in the repository — then whoever can edit the script still cannot make it serve,
 *       which is exactly the gap that let this happen.</li>
 * </ul>
 *
 * <p>The shape checks alone would have blocked the August payload with no configuration at all.
 */
@Service
public class InstallScriptIntegrity {

    private static final Logger logger = LoggerFactory.getLogger(InstallScriptIntegrity.class);

    /**
     * Floor rather than an exact count, so ordinary edits to the installer don't trip it. The real
     * file is around 1550 lines; the malicious replacement was 22. Anything under this is not a
     * plausible edit of a 1550-line script.
     */
    private static final int MIN_PLAUSIBLE_LINES = 800;

    /** Comment lines are excluded before scanning — see stripComments. */
    private static final List<Pattern> DROPPER_PATTERNS = List.of(
            // Staging a downloaded payload as executable under a temp path.
            Pattern.compile("chmod\\s+(\\+x|[0-7]{3,4})\\s+/tmp/"),
            // Piping a download straight into an interpreter.
            Pattern.compile("(curl|wget)[^|]*\\|\\s*(sudo\\s+)?(ba)?sh(\\s|$)"),
            // Fetching from a bare IP instead of a named host.
            Pattern.compile("(curl|wget).*https?://\\d{1,3}(\\.\\d{1,3}){3}"),
            // Hidden dotfile destinations, as used by the August payload (/tmp/.svc).
            Pattern.compile("-(o|O)\\s+\\S*/\\.[A-Za-z0-9_-]+\\b")
    );

    /**
     * Expected SHA-256 of the raw script resource, hex, lower case. Empty disables digest pinning
     * and leaves only the shape checks active.
     */
    @Value("${xamops.agent.install-script.sha256:}")
    private String expectedSha256;

    /** Thrown when the script must not be served. */
    public static class TamperedScriptException extends RuntimeException {
        public TamperedScriptException(String message) { super(message); }
    }

    /**
     * Validates the raw script exactly as read from the classpath, before any provider
     * substitution, so the pinned digest is stable across the three endpoints.
     *
     * @throws TamperedScriptException if the content must not be served
     */
    public void verify(String rawScript) {
        if (rawScript == null || rawScript.isBlank()) {
            throw new TamperedScriptException("install script is empty");
        }

        long lines = rawScript.lines().count();
        if (lines < MIN_PLAUSIBLE_LINES) {
            throw new TamperedScriptException("install script is " + lines + " lines; expected at "
                    + "least " + MIN_PLAUSIBLE_LINES + ". A customer-facing installer does not "
                    + "shrink by half — treating this as a supply-chain replacement.");
        }

        String code = stripComments(rawScript);
        for (Pattern p : DROPPER_PATTERNS) {
            var m = p.matcher(code);
            if (m.find()) {
                throw new TamperedScriptException("install script contains a remote-code-execution "
                        + "pattern that the real installer never uses: \"" + m.group().trim() + "\"");
            }
        }

        if (!expectedSha256.isBlank()) {
            String actual = sha256(rawScript);
            if (!MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.UTF_8),
                    expectedSha256.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
                throw new TamperedScriptException("install script digest " + actual
                        + " does not match the pinned xamops.agent.install-script.sha256");
            }
        }
    }

    /** Hex SHA-256 of the given content. Also served so customers can verify what they downloaded. */
    public String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Blanks whole-line shell comments before pattern matching.
     *
     * <p>The installer explains in prose why {@code curl | bash} has to keep working for Cloud
     * Shell users, and matching that sentence would refuse to serve the legitimate file. Line
     * structure is preserved so reported matches stay meaningful.
     */
    private static String stripComments(String script) {
        StringBuilder sb = new StringBuilder(script.length());
        script.lines().forEach(line -> {
            if (!line.stripLeading().startsWith("#")) sb.append(line);
            sb.append('\n');
        });
        return sb.toString();
    }

    /** Logs the digest at startup so the value to pin is easy to obtain from a trusted deploy. */
    public void logDigest(String rawScript) {
        logger.info("install-k8s.sh sha256={} ({} lines){}",
                sha256(rawScript), rawScript.lines().count(),
                expectedSha256.isBlank() ? " — digest pinning is OFF; set "
                        + "xamops.agent.install-script.sha256 to enable it" : " — pinned");
    }
}
