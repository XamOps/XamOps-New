package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.OpenObserveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityController.class);

    private final OpenObserveService openObserveService;
    private final ClientRepository clientRepository;

    public ObservabilityController(OpenObserveService openObserveService, ClientRepository clientRepository) {
        this.openObserveService = openObserveService;
        this.clientRepository = clientRepository;
    }

    // ── Provision + Config ────────────────────────────────────────────────────

    /**
     * GET /api/observability/config[?providerAccountId=xxx]
     * Returns observability config. Auto-provisions O2 org on first call.
     * If providerAccountId is given, provisions a per-account O2 org.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            Long clientId = userDetails.getClientId();
            Client client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client not found"));

            if (providerAccountId != null && !providerAccountId.isBlank()) {
                try {
                    AccountObservabilityConfig accConfig =
                            openObserveService.provisionForAccount(client, providerAccountId);
                    ObservabilityConfig clientConfig = openObserveService.provisionTenant(client);
                    return ResponseEntity.ok(toAccountConfigResponse(accConfig, clientConfig));
                } catch (Exception e) {
                    logger.warn("Account provisioning failed for {}, falling back to client config",
                            providerAccountId, e);
                    // Fall through to client-level config below
                }
            }

            ObservabilityConfig config = openObserveService.provisionTenant(client);
            return ResponseEntity.ok(toConfigResponse(config));
        } catch (Exception e) {
            logger.error("getConfig failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    /**
     * PUT /api/observability/config
     * Update enabled toggles and retention (client-level settings).
     */
    @PutMapping("/config")
    public ResponseEntity<?> updateConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> body) {
        try {
            Long clientId = userDetails.getClientId();
            int retentionDays = (int) body.getOrDefault("retentionDays", 30);

            ObservabilityConfig config = openObserveService.updateConfig(clientId, retentionDays);
            return ResponseEntity.ok(toConfigResponse(config));
        } catch (Exception e) {
            logger.error("updateConfig failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<?> getOverview(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok(
                    openObserveService.getOverviewStats(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getOverview failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    @GetMapping("/streams")
    public ResponseEntity<?> listStreams(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            List<Map<String, Object>> streams =
                    openObserveService.listStreams(userDetails.getClientId(), providerAccountId);
            return ResponseEntity.ok(streams);
        } catch (Exception e) {
            logger.error("listStreams failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    /**
     * POST /api/observability/logs/search
     * Body: { stream, keyword, level, startTime, endTime, size, providerAccountId? }
     */
    @PostMapping("/logs/search")
    public ResponseEntity<?> searchLogs(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            Map<String, Object> result = openObserveService.searchLogs(userDetails.getClientId(), params);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("searchLogs failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    /**
     * POST /api/observability/logs/histogram
     * Body: { stream, startTime, endTime, providerAccountId? }
     */
    @PostMapping("/logs/histogram")
    public ResponseEntity<?> getLogHistogram(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            Map<String, Object> result = openObserveService.getLogHistogram(userDetails.getClientId(), params);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("getLogHistogram failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @GetMapping("/metrics/query")
    public ResponseEntity<?> queryMetrics(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam String query,
            @RequestParam(defaultValue = "0") long startTime,
            @RequestParam(defaultValue = "0") long endTime,
            @RequestParam(defaultValue = "60s") String step,
            @RequestParam(required = false) String providerAccountId) {
        try {
            long start = startTime > 0 ? startTime : System.currentTimeMillis() - 3600_000L;
            long end   = endTime   > 0 ? endTime   : System.currentTimeMillis();
            Map<String, Object> result = openObserveService.queryMetrics(
                    userDetails.getClientId(), providerAccountId, query, start, end, step);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("queryMetrics failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Cluster Discovery ─────────────────────────────────────────────────────

    @GetMapping("/clusters")
    public ResponseEntity<?> getClusters(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok(
                    openObserveService.getClusters(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getClusters failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Setup / Collector Configs ─────────────────────────────────────────────

    @GetMapping("/setup/fluent-bit")
    public ResponseEntity<String> getFluentBitConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateFluentBitConfig(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getFluentBitConfig failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    @GetMapping("/setup/helm")
    public ResponseEntity<String> getHelmValues(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateHelmValues(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getHelmValues failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    @GetMapping("/setup/otel")
    public ResponseEntity<String> getOtelConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateOtelCollectorConfig(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getOtelConfig failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    /**
     * GET /api/observability/setup/helm-eks
     * Returns the Helm install command for the OpenObserve collector on EKS.
     */
    @GetMapping("/setup/helm-eks")
    public ResponseEntity<String> getHelmEksCommand(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateHelmEksCommand(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getHelmEksCommand failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    /**
     * GET /api/observability/setup/ec2-otel
     * Returns the OTel Collector install script + config for a Linux EC2 instance.
     */
    @GetMapping("/setup/ec2-otel")
    public ResponseEntity<String> getEc2OtelConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateEc2OtelConfig(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getEc2OtelConfig failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    /**
     * GET /api/observability/setup/gce-otel
     * Returns the OTel Collector install script + config for a Linux GCE instance.
     */
    @GetMapping("/setup/gce-otel")
    public ResponseEntity<String> getGceOtelConfig(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateGceOtelConfig(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getGceOtelConfig failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    /**
     * GET /api/observability/setup/agent-secret
     * Returns a ready-to-apply K8s Secret YAML with org ID + credentials pre-filled.
     */
    @GetMapping("/setup/agent-secret")
    public ResponseEntity<String> getAgentSecret(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/plain")
                    .body(openObserveService.generateAgentSecret(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("getAgentSecret failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    // ── Agent manifest serving ────────────────────────────────────────────────

    @GetMapping("/setup/agents/{file}")
    public ResponseEntity<String> getAgentManifest(@PathVariable String file) {
        String resource = switch (file) {
            case "rbac"           -> "agents/rbac.yaml";
            case "xamops-agent"   -> "agents/xamops-agent.yaml";
            // legacy names kept for backward compatibility
            case "fluent-bit"     -> "agents/fluent-bit.yaml";
            case "otel-collector" -> "agents/otel-collector.yaml";
            default -> null;
        };
        if (resource == null) return ResponseEntity.notFound().build();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return ResponseEntity.notFound().build();
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().header("Content-Type", "text/plain").body(content);
        } catch (Exception e) {
            logger.error("getAgentManifest failed", e);
            return ResponseEntity.internalServerError().body("An internal error occurred. Please contact support.");
        }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<?> listAlerts(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok(
                    openObserveService.listAlerts(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("listAlerts failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    @PostMapping("/alerts")
    public ResponseEntity<?> createAlert(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId,
            @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(
                    openObserveService.createAlert(userDetails.getClientId(), providerAccountId, body));
        } catch (Exception e) {
            logger.error("createAlert failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    @DeleteMapping("/alerts/{streamType}/{streamName}/{alertName}")
    public ResponseEntity<?> deleteAlert(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId,
            @PathVariable String streamType,
            @PathVariable String streamName,
            @PathVariable String alertName) {
        try {
            openObserveService.deleteAlert(userDetails.getClientId(), providerAccountId, streamType, streamName, alertName);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (Exception e) {
            logger.error("deleteAlert failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    @PutMapping("/alerts/{streamType}/{streamName}/{alertName}/toggle")
    public ResponseEntity<?> toggleAlert(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId,
            @PathVariable String streamType,
            @PathVariable String streamName,
            @PathVariable String alertName,
            @RequestParam boolean enable) {
        try {
            openObserveService.toggleAlert(userDetails.getClientId(), providerAccountId, streamType, streamName, alertName, enable);
            return ResponseEntity.ok(Map.of("enabled", enable));
        } catch (Exception e) {
            logger.error("toggleAlert failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Alert Destinations ────────────────────────────────────────────────────

    @GetMapping("/alert-destinations")
    public ResponseEntity<?> listDestinations(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok(
                    openObserveService.listDestinations(userDetails.getClientId(), providerAccountId));
        } catch (Exception e) {
            logger.error("listDestinations failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    @PostMapping("/alert-destinations")
    public ResponseEntity<?> saveDestination(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId,
            @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(
                    openObserveService.saveDestination(userDetails.getClientId(), providerAccountId, body));
        } catch (Exception e) {
            logger.error("saveDestination failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    @DeleteMapping("/alert-destinations/{name}")
    public ResponseEntity<?> deleteDestination(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId,
            @PathVariable String name) {
        try {
            openObserveService.deleteDestination(userDetails.getClientId(), providerAccountId, name);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (Exception e) {
            logger.error("deleteDestination failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "An internal error occurred. Please contact support."));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toConfigResponse(ObservabilityConfig config) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("orgId",         config.getO2OrgId() != null ? config.getO2OrgId() : "");
        m.put("retentionDays", config.getRetentionDays());
        m.put("provisionedAt", config.getCreatedAt() != null ? config.getCreatedAt().toString() : "");
        return m;
    }

    private Map<String, Object> toAccountConfigResponse(AccountObservabilityConfig accConfig,
                                                         ObservabilityConfig clientConfig) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("orgId",         accConfig.getO2OrgId() != null ? accConfig.getO2OrgId() : "");
        m.put("accountName",   accConfig.getAccountName() != null ? accConfig.getAccountName() : "");
        m.put("retentionDays", clientConfig.getRetentionDays());
        m.put("provisionedAt", accConfig.getCreatedAt() != null ? accConfig.getCreatedAt().toString() : "");
        return m;
    }
}
