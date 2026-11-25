package com.xammer.cloud.xampulse.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The install script that runs on customers' hosts: order of operations, verification, hardening, and no secret. */
class SsmSnippetTest {

    private static final String LINUX = XamPulseTemplateTest.raw("xampulse/ssm-snippets/install-linux.sh");
    private static final String WINDOWS = XamPulseTemplateTest.raw("xampulse/ssm-snippets/install-windows.ps1");
    private static final Pattern SECRET = Pattern.compile("AKIA[A-Z0-9]{16}|ASIA[A-Z0-9]{16}|(?i)bearer\\s+[a-z0-9._-]{8,}|xak_|eyJ[A-Za-z0-9_-]{10,}|-----BEGIN|password\\s*=\\s*\\S+");

    @Test
    void asksTheServerForTheDesiredStateBeforeDownloadingAnything() {
        int ask = LINUX.indexOf("--data-urlencode \"instanceId=");
        int download = LINUX.indexOf("${RELEASE_BASE_URL}/latest/SHA256SUMS");
        int install = LINUX.indexOf("install -m 0755");
        assertTrue(ask > 0 && download > ask && install > download, "desired-state check must come first");
        assertTrue(LINUX.contains("\"${ANSWER}\" = \"SKIP\""));
        assertTrue(LINUX.contains("\"${ANSWER}\" != \"INSTALL\""), "anything but INSTALL must not install");
    }

    @Test
    void honoursTheDisabledMarkerWhenTheServerCannotBeReached() {
        assertTrue(LINUX.contains("MARKER=\"${STATE_DIR}/disabled\""));
        assertTrue(LINUX.contains("[ -e \"${MARKER}\" ]"));
        int unreachable = LINUX.indexOf("# Server unreachable or unexpected answer");
        int removeMarker = LINUX.indexOf("rm -f \"${MARKER}\"");
        assertTrue(unreachable > 0 && removeMarker > unreachable, "the marker is removed only after the server said INSTALL");
        // SKIP writes the marker and removes an installed agent so the association cannot undo a disconnect.
        String skip = LINUX.substring(LINUX.indexOf("if [ \"${ANSWER}\" = \"SKIP\" ]"), unreachable);
        assertTrue(skip.contains(": > \"${MARKER}\"") && skip.contains("rm -rf \"${INSTALL_DIR}\"") && skip.contains("exit 0"));
    }

    @Test
    void verifiesTheSha256OfEveryDownloadedBinaryAndTheSignatureWhenPossible() {
        assertTrue(LINUX.contains("sha256sum \"${TMP}/${f}\""));
        assertTrue(LINUX.contains("checksum mismatch"));
        assertTrue(LINUX.contains("cosign verify-blob --key"));
        assertTrue(LINUX.contains("WARNING: release signature not verified"), "checksum-only installs say so");
        int verify = LINUX.indexOf("checksum mismatch");
        int place = LINUX.indexOf("install -m 0755");
        assertTrue(verify < place, "nothing is installed before it was verified");
    }

    @Test
    void usesImdsV2OnlyAndFailsClosedWithoutIt() {
        assertTrue(LINUX.contains("-X PUT \"http://169.254.169.254/latest/api/token\""));
        assertTrue(LINUX.contains("X-aws-ec2-metadata-token: ${IMDS_TOKEN}"));
        assertFalse(LINUX.contains("/latest/meta-data/iam/security-credentials"), "the installer never reads credentials");
        assertTrue(LINUX.indexOf("-z \"${IMDS_TOKEN}\"") > 0);
    }

    @Test
    void systemdUnitIsHardenedAndStopsWhenTheMarkerExists() {
        for (String line : new String[] {"User=xampulse", "NoNewPrivileges=true", "ProtectSystem=strict", "ProtectHome=true", "PrivateTmp=true",
                "ReadWritePaths=${STATE_DIR} ${LOG_DIR}", "CapabilityBoundingSet=CAP_DAC_READ_SEARCH", "ConditionPathExists=!${MARKER}",
                "RestartPreventExitStatus=78"}) {
            assertTrue(LINUX.contains(line), "unit is missing " + line);
        }
        assertFalse(LINUX.contains("User=root"));
    }

    @Test
    void containsNoTokenOrKeyAndPassesNoSecretOnTheCommandLine() {
        assertFalse(SECRET.matcher(LINUX).find(), "install script must contain no secret");
        assertFalse(SECRET.matcher(WINDOWS).find());
        assertFalse(LINUX.contains("--token") || LINUX.contains("--api-key") || LINUX.contains("agentApiKey"));
    }

    @Test
    void failsFastAndOnlyTouchesItsOwnPaths() {
        assertTrue(LINUX.contains("set -eu"));
        assertTrue(LINUX.contains("trap 'rm -rf \"${TMP}\"' EXIT"));
        assertFalse(LINUX.contains("rm -rf /"), "no broad deletes");
        assertTrue(LINUX.contains("{{ ReleaseBaseUrl }}") && LINUX.contains("{{ DesiredStateUrl }}"));
    }

    @Test
    void windowsIsAPlaceholderThatDoesNothing() {
        assertTrue(WINDOWS.contains("Windows support is planned"));
        assertTrue(WINDOWS.contains("exit 0"));
        assertFalse(WINDOWS.toLowerCase().contains("invoke-webrequest") || WINDOWS.toLowerCase().contains("new-service"));
    }

    @Test
    void theAgentScriptsFolderCarriesIdenticalCopiesOfTheSnippets() throws IOException {
        Path agentScripts = Path.of("..", "xamops-agent", "scripts");
        assertEquals(LINUX, Files.readString(agentScripts.resolve("xampulse_install_linux.sh"), StandardCharsets.UTF_8),
                "run scripts/xampulse/sync-snippets.sh");
        assertEquals(WINDOWS, Files.readString(agentScripts.resolve("xampulse_install_windows.ps1"), StandardCharsets.UTF_8),
                "run scripts/xampulse/sync-snippets.sh");
    }

    @Test
    void uninstallScriptLeavesTheMarkerAndNeverUsesAToken() throws IOException {
        String u = Files.readString(Path.of("..", "xamops-agent", "scripts", "xampulse_uninstall.sh"), StandardCharsets.UTF_8);
        assertTrue(u.contains(": > \"${STATE_DIR}/disabled\""));
        assertTrue(u.contains("systemctl stop xampulse-agent.service"));
        assertFalse(SECRET.matcher(u).find());
    }
}
