package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AgentApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final AgentApiKeyService agentApiKeyService;
    private final CloudAccountRepository cloudAccountRepository;

    public AgentInstallConfigController(AgentApiKeyService agentApiKeyService,
                                        CloudAccountRepository cloudAccountRepository) {
        this.agentApiKeyService = agentApiKeyService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * GET /api/xamops/agent-install/config[?accountId=111122223333]
     *
     * <p>Returns the key the guides paste into their install commands. With an {@code accountId}
     * that belongs to the caller, that is an account-scoped key, minted on first request — so the
     * customer never sees or types a key, they copy a command that already contains the right
     * one. Without a resolvable account the shared legacy key is returned, because a guide with
     * no key at all is useless; {@code keyScope} tells the caller which it got.
     *
     * <p>{@code reachableFromCluster} is false when the configured URL is a loopback or
     * private address. An agent running inside a managed Kubernetes cluster cannot reach
     * {@code http://localhost:8080}, so the guide uses this flag to tell the user they need
     * a tunnel rather than silently handing them a manifest that can never connect.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String accountId) {

        String key = agentApiKey;
        String keyScope = "legacy-global";

        if (userDetails != null && accountId != null && !accountId.isBlank()) {
            CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId).orElse(null);
            // Ownership is checked here as well as in AgentKeyController: this endpoint hands out
            // a credential, so it must not become a way to read another tenant's key by passing
            // their account id.
            if (account != null && account.getClient() != null
                    && account.getClient().getId().equals(userDetails.getClientId())) {
                try {
                    key = agentApiKeyService.getOrCreate(account.getId());
                    keyScope = "account";
                } catch (RuntimeException e) {
                    log.warn("Could not issue a scoped agent key for account {}; falling back to "
                            + "the shared key: {}", accountId, e.getMessage());
                }
            } else {
                log.warn("Agent install config requested for account {} not owned by client {}",
                        accountId, userDetails.getClientId());
            }
        }

        return ResponseEntity.ok(Map.of(
                "serverUrl", xamopsServerUrl,
                "apiKey", key,
                "keyScope", keyScope,
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
