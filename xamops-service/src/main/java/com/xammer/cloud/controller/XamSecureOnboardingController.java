package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.UpwindApiClient;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/xamsecure/onboarding")
public class XamSecureOnboardingController {

    private final XamSecureService xamSecureService;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final UpwindApiClient apiClient;
    private final com.xammer.cloud.service.upwind.UpwindOnboardingService onboardingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Setter
    @Value("${upwind.webhook.secret:}")
    private String webhookSecret;

    @Setter
    @Value("${xamops.public.base-url:http://localhost:8080}")
    private String publicBaseUrl;

    public XamSecureOnboardingController(XamSecureService xamSecureService,
                                          UpwindAccountConnectionRepository connectionRepo,
                                          UpwindApiClient apiClient,
                                          com.xammer.cloud.service.upwind.UpwindOnboardingService onboardingService) {
        this.xamSecureService = xamSecureService;
        this.connectionRepo = connectionRepo;
        this.apiClient = apiClient;
        this.onboardingService = onboardingService;
    }

    /** Customer-facing: connection + sensor status for the caller's own accounts. */
    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(xamSecureService.getOnboardingStatus(user.getClientId()));
    }

    /**
     * Customer-facing: the host sensor install command.
     *
     * <p>On demand rather than in {@code /status}, for the same reason the CloudFormation launch
     * URL is: the script embeds Xammer's shared sensor client secret, and {@code /status} is a
     * cacheable GET. POST plus {@code no-store} keeps it out of browser history and caches.
     *
     * <p>Not account-scoped, deliberately. Upwind's host script is identical for every host in
     * the organization — there is nothing in it keyed by cloud account — so it is returned once
     * rather than per account, where it would imply a scoping that does not exist.
     */
    @PostMapping("/sensor-command")
    public ResponseEntity<?> sensorCommand(@AuthenticationPrincipal ClientUserDetails user) {
        if (!onboardingService.isAllowedForClient(user.getClientId())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Security monitoring is not enabled for this account."));
        }
        if (!onboardingService.isSensorConfigured()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Sensor deployment is not configured. Contact your XamOps administrator."));
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(Map.of("command", onboardingService.renderSensorInstallCommand(),
                             "target", "host"));
    }

    /**
     * Customer-facing: a pre-filled CloudFormation quick-create URL for the ECS sensor stack.
     *
     * <b>The returned URL contains the shared Sensor credential in its query string.</b> Treat it
     * as a credential — hence POST and {@code no-store}.
     */
    @PostMapping("/ecs-launch-url")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> ecsLaunchUrl(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestBody Map<String, Object> body) {
        if (!onboardingService.isAllowedForClient(user.getClientId())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Security monitoring is not enabled for this account."));
        }
        Object rawSubnets = body.get("subnets");
        List<String> subnets = rawSubnets instanceof List<?> l
                ? l.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList()
                : List.of();
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(onboardingService.ecsLaunchUrl(
                            user.getClientId(),
                            str(body, "cloudAccountId"),
                            str(body, "launchType"),
                            str(body, "clusterName"),
                            str(body, "vpcId"),
                            str(body, "vpcCidr"),
                            subnets,
                            str(body, "region")));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Every one of these is something the customer or an admin can fix — a bad subnet id,
            // a missing template URL — so the reason is returned rather than swallowed.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Customer-facing: resolved CloudFormation parameters for the ECS sensor stack.
     *
     * POST and {@code no-store} for the same reason as the Kubernetes command — the body carries
     * the shared Sensor credential.
     */
    @PostMapping("/ecs-deployment")
    public ResponseEntity<?> ecsDeployment(@AuthenticationPrincipal ClientUserDetails user,
                                          @RequestBody Map<String, String> body) {
        if (!onboardingService.isAllowedForClient(user.getClientId())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Security monitoring is not enabled for this account."));
        }
        if (!onboardingService.isSensorConfigured()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Sensor deployment is not configured. Contact your XamOps administrator."));
        }
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required."));
        }
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(onboardingService.resolveEcsDeployment(
                            user.getClientId(), cloudAccountId, body.get("launchType")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Customer-facing: the Kubernetes sensor survey command for one of the caller's own accounts.
     *
     * POST rather than GET because it is parameterized by account and cluster, and because the
     * response body carries the shared Sensor credential — a GET would land in browser history and
     * in any intermediary that logs query strings. {@code no-store} for the same reason.
     */
    @PostMapping("/kubernetes-command")
    public ResponseEntity<?> kubernetesCommand(@AuthenticationPrincipal ClientUserDetails user,
                                              @RequestBody Map<String, String> body) {
        if (!onboardingService.isAllowedForClient(user.getClientId())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Security monitoring is not enabled for this account."));
        }
        if (!onboardingService.isSensorConfigured()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Sensor deployment is not configured. Contact your XamOps administrator."));
        }
        String cloudAccountId = body.get("cloudAccountId");
        String clusterName = body.get("clusterName");
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required."));
        }
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(onboardingService.renderKubernetesSurveyCommand(
                            user.getClientId(), cloudAccountId, clusterName));
        } catch (IllegalArgumentException e) {
            // Unsupported provider or a malformed cluster name — the customer can fix both, so the
            // reason is worth returning rather than collapsing into a generic 400.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Customer-facing: disconnect one account from XamSecure.
     *
     * Deregisters upstream, then tells the customer to delete their stack. Deleting the stack on
     * its own is NOT sufficient — Upwind's template Delete handler calls no API, so the
     * registration would survive and Upwind would keep assuming a role that no longer exists.
     */
    @PostMapping("/aws/disconnect")
    public ResponseEntity<?> awsDisconnect(@AuthenticationPrincipal ClientUserDetails user,
                                           @RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        try {
            return ResponseEntity.ok(
                    onboardingService.disconnect(user.getClientId(), cloudAccountId));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Customer-facing: the CloudFormation launch URL that connects one of the caller's own AWS
     * accounts to XamSecure.
     *
     * <p><b>The response body carries Xammer's shared Upwind client secret in the URL's query
     * string.</b> That is unavoidable — the template takes the credentials as stack parameters —
     * but it makes the response itself a credential. Hence POST rather than GET (keeps it out of
     * browser history and access logs), no caching, and nothing about the URL is logged or
     * persisted server-side. The customer never types or sees the credential as a field: it
     * arrives pre-filled in the CloudFormation console form.
     *
     * <p>Ownership is enforced against the caller's own accounts in the service, so passing
     * another tenant's account id yields 403 rather than a launch URL for someone else's account.
     */
    @PostMapping("/aws/launch-url")
    public ResponseEntity<?> awsLaunchUrl(@AuthenticationPrincipal ClientUserDetails user,
                                          @RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        try {
            return ResponseEntity.ok()
                    .header("Cache-Control", "no-store")
                    .body(onboardingService.createLaunchUrl(user.getClientId(), cloudAccountId));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Customer-facing: has the connection come up yet?
     *
     * Answered by asking Upwind whether it reports any asset for the account, which is the only
     * reachable signal — there is no cloud-account status API. The stack registers the account
     * itself, so this is genuinely the confirmation, not a proxy for one.
     */
    @PostMapping("/aws/check")
    public ResponseEntity<?> awsCheck(@AuthenticationPrincipal ClientUserDetails user,
                                      @RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        try {
            return ResponseEntity.ok(
                    onboardingService.checkConnection(user.getClientId(), cloudAccountId));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Admin-only: register/update an Upwind connection for a cloud account.
     * Phase 1 fallback flow — a Xammer admin creates the Cloud account /
     * Sensor credential in the Upwind console and registers the resulting
     * install command here; customers then see it in their Onboarding tab.
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(UpwindAccountConnection::new);
        conn.setCloudAccountId(cloudAccountId);
        if (body.get("status") != null) conn.setStatus(body.get("status"));
        if (body.get("sensorInstallCommand") != null) conn.setSensorInstallCommand(body.get("sensorInstallCommand"));
        if (body.get("notes") != null) conn.setNotes(body.get("notes"));
        conn.setUpdatedAt(Instant.now());
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /** Name we register under; also the key used to detect an existing registration. */
    public static final String WEBHOOK_NAME = "XamOps XamSecure";

    /**
     * Admin-only: register Upwind's real-time detection webhook pointing at our ingest
     * endpoint, authenticated with a bearer token equal to the shared upwind.webhook.secret.
     *
     * Uses the documented v2 shape: {@code POST /v2/{org}/integrations/webhooks} with a
     * LOWERCASE vendor ({@code custom}) and the url/authentication nested under
     * {@code config}, whose {@code authentication.type} is likewise lowercase
     * ({@code bearer}). The previous implementation posted uppercase values to the v1
     * {@code /integration-webhooks} path.
     *
     * Idempotent: the webhook search runs first, so re-running this does not accumulate
     * duplicate registrations that would each deliver the same event.
     */
    @PostMapping("/register-webhook")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> registerWebhook() {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "upwind.webhook.secret not configured"));
        }
        try {
            for (JsonNode existing : apiClient.searchWebhooks(200)) {
                if (WEBHOOK_NAME.equals(existing.path("name").asText(null))) {
                    return ResponseEntity.ok(Map.of(
                            "webhookId", existing.path("id").asText(),
                            "status", "already-registered"));
                }
            }
        } catch (Exception e) {
            // A search failure shouldn't block registration; worst case we create a duplicate.
            log.warn("[Upwind] webhook search failed before registration: {}", e.getMessage());
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", WEBHOOK_NAME);
        body.put("vendor", "custom");
        ObjectNode config = body.putObject("config");
        config.put("url", publicBaseUrl + "/api/xamsecure/webhook/upwind");
        ObjectNode auth = config.putObject("authentication");
        auth.put("type", "bearer");
        auth.put("token", webhookSecret);
        try {
            JsonNode created = apiClient.createWebhook(body);
            return ResponseEntity.ok(Map.of(
                    "webhookId", created.path("id").asText(), "status", "created"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: list the webhooks registered on the Upwind org, for troubleshooting. */
    @GetMapping("/webhooks")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> listWebhooks() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode w : apiClient.searchWebhooks(200)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.path("id").asText(null));
                m.put("name", w.path("name").asText(null));
                m.put("vendor", w.path("vendor").asText(null));
                m.put("status", w.path("status").asText(null));
                m.put("url", w.path("config").path("url").asText(null));
                m.put("createTime", w.path("create_time").asText(null));
                rows.add(m);
            }
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: enable/disable or rename a registered webhook. */
    @PatchMapping("/webhooks/{webhookId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> patchWebhook(@PathVariable String webhookId,
                                          @RequestBody Map<String, String> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body.get("name") != null) patch.put("name", body.get("name"));
        String status = body.get("status");
        if (status != null) {
            if (!"enabled".equals(status) && !"disabled".equals(status)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "status must be 'enabled' or 'disabled'"));
            }
            patch.put("status", status);
        }
        if (patch.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name or status is required"));
        }
        try {
            return ResponseEntity.ok(apiClient.updateWebhook(webhookId, patch));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: remove a registered webhook. */
    @DeleteMapping("/webhooks/{webhookId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> deleteWebhook(@PathVariable String webhookId) {
        try {
            apiClient.deleteWebhook(webhookId);
            return ResponseEntity.ok(Map.of("status", "deleted", "webhookId", webhookId));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
