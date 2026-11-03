package com.xammer.cloud.service.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Renders the one-line installer command shown with a freshly issued token (design §4.2). The
 * command carries the short-lived connect token (never a long-lived key, AC-2); the installer
 * exchanges it for the agent key.
 */
@Service
public class ScriptCommandRenderer {

    @Value("${xamops.server.url:https://api.xamops.com}")
    private String serverUrl;

    /** The connect / enable command for a token of the given purpose. */
    public String render(String purpose, String clusterName, String region, String rawToken,
                         boolean createIam) {
        String phase = switch (purpose) {
            case ConnectTokenService.PURPOSE_ENABLE_AUTOMATION,
                 ConnectTokenService.PURPOSE_ROTATE_AUTOMATION_KEY -> "automation";
            default -> "read-only";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("curl -fsSL ").append(serverUrl).append("/api/v1/agent/install/eks.sh | bash -s --")
                .append(" --phase ").append(phase)
                .append(" --cluster ").append(clusterName)
                .append(" --region ").append(region)
                .append(" --connect-token ").append(rawToken);
        if (ConnectTokenService.PURPOSE_ROTATE_DATA_KEY.equals(purpose)) {
            sb.append(" --rotate-key data");
        } else if (ConnectTokenService.PURPOSE_ROTATE_AUTOMATION_KEY.equals(purpose)) {
            sb.append(" --rotate-key automation");
        } else if (createIam) {
            sb.append(" --create-iam");
        }
        return sb.toString();
    }

    /** The read-only re-run command for disable-automation / offboard (no token needed). */
    public String renderReadOnly(String clusterName, String region, boolean uninstall) {
        StringBuilder sb = new StringBuilder();
        sb.append("curl -fsSL ").append(serverUrl).append("/api/v1/agent/install/eks.sh | bash -s --");
        if (uninstall) {
            sb.append(" --uninstall");
        } else {
            sb.append(" --phase read-only");
        }
        sb.append(" --cluster ").append(clusterName).append(" --region ").append(region);
        return sb.toString();
    }
}
