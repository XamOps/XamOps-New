package com.xammer.cloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Supplies the environment-specific values the in-app setup guides need to render a
 * correct agent install manifest.
 *
 * <p>Previously the AKS/EKS/GKE setup modals hardcoded {@code https://live.xamops.com} and
 * the agent API key as literal strings in JSX. That made the guide structurally incapable
 * of being right for more than one environment: a developer running locally, or a tenant on
 * UAT, would copy a manifest that pointed their cluster at production. This endpoint lets
 * the guide read whatever {@code xamops.server.url} the running profile defines
 * (application-local / -uat / -prod), so the rendered manifest always matches the backend
 * the user is actually looking at.
 *
 * <p>Deliberately served from {@code /api/xamops/**} rather than {@code /api/v1/agent/**}:
 * the latter is exempt from session authentication (agents authenticate with a bearer key),
 * which would leave the API key readable without logging in.
 */
@Slf4j
@RestController
@RequestMapping("/api/xamops/agent-install")
public class AgentInstallConfigController {

    @Value("${xamops.server.url:https://api.xamops.com}")
    private String xamopsServerUrl;

    @Value("${xamops.agent.api-key:xamops_DEFAULT_KEY_CHANGE_IN_PRODUCTION}")
    private String agentApiKey;

    /**
     * GET /api/xamops/agent-install/config
     *
     * <p>{@code reachableFromCluster} is false when the configured URL is a loopback or
     * private address. An agent running inside a managed Kubernetes cluster cannot reach
     * {@code http://localhost:8080}, so the guide uses this flag to tell the user they need
     * a tunnel rather than silently handing them a manifest that can never connect.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "serverUrl", xamopsServerUrl,
                "apiKey", agentApiKey,
                "reachableFromCluster", isReachableFromCluster(xamopsServerUrl)
        ));
    }

    /**
     * True unless the host is obviously unroutable from outside the developer's machine.
     * Intentionally conservative — it only flags the cases that definitely cannot work
     * (loopback and RFC1918 ranges), rather than trying to prove reachability.
     */
    private boolean isReachableFromCluster(String url) {
        if (url == null || url.isBlank()) return false;
        String host = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.toLowerCase();

        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0.0.0.0") || host.endsWith(".local")) {
            return false;
        }
        // RFC1918 private ranges: 10.x, 192.168.x, and 172.16-31.x
        if (host.startsWith("10.") || host.startsWith("192.168.")) return false;
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length > 1) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) return false;
                } catch (NumberFormatException ignored) {
                    // Not an IP literal — treat as a hostname, which is routable.
                }
            }
        }
        return true;
    }
}
