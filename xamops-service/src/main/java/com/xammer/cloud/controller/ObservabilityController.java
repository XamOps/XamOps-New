package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AccountObservabilityConfig;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.ObservabilityConfig;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AgentHealthService;
import com.xammer.cloud.service.ObservabilityRetentionReconciler;
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
    private final ObservabilityRetentionReconciler retentionReconciler;
    private final AgentHealthService agentHealthService;

    public ObservabilityController(OpenObserveService openObserveService,
                                   ClientRepository clientRepository,
                                   ObservabilityRetentionReconciler retentionReconciler,
                                   AgentHealthService agentHealthService) {
        this.openObserveService = openObserveService;
        this.clientRepository = clientRepository;
        this.retentionReconciler = retentionReconciler;
        this.agentHealthService = agentHealthService;
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
                    // Client-level config first: provisionForAccount inherits its retention
                    // setting, so provisioning the account org first left the account org on
                    // OpenObserve's 7-day default on every brand-new tenant.
                    ObservabilityConfig clientConfig = openObserveService.provisionTenant(client);
                    AccountObservabilityConfig accConfig =
                            openObserveService.provisionForAccount(client, providerAccountId);
                    return ResponseEntity.ok(toAccountConfigResponse(accConfig, clientConfig));
                } catch (Exception e) {
                    logger.warn("Account provisioning failed for {}, falling back to client config: {}",
                            providerAccountId, e.getMessage());

                    // Tell the caller we fell back. This used to be a log line only, so the
                    // user was shown organisation-wide data (or nothing) for a selected
                    // account with no indication that the account itself isn't provisioned —
                    // indistinguishable from "the account has no data".
                    ObservabilityConfig fallback = openObserveService.provisionTenant(client);
                    Map<String, Object> body = toConfigResponse(fallback);
                    body.put("scope", "client");
                    body.put("requestedAccountId", providerAccountId);
                    body.put("accountProvisioned", false);
                    body.put("fallbackReason", "This account is not provisioned for observability yet, "
                            + "so organisation-wide data is being shown.");
                    return ResponseEntity.ok(body);
                }
            }

            ObservabilityConfig config = openObserveService.provisionTenant(client);
            return ResponseEntity.ok(toConfigResponse(config));
        } catch (Exception e) {
            return errorResponse("getConfig", e);
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

            // Push to OpenObserve in the background — hundreds of sequential HTTP calls for a
            // tenant with several accounts and many streams, which would exceed the nginx
            // read timeout if the user had to wait. Driven from here rather than from inside
            // updateConfig so the reconciler depends on the service and not the other way
            // round; a mutual dependency would need @Lazy and risk a startup failure.
            retentionReconciler.applyRetentionToOrgs(
                    openObserveService.listOrgIdsForClient(clientId), retentionDays);

            return ResponseEntity.ok(toConfigResponse(config));
        } catch (Exception e) {
            return errorResponse("updateConfig", e);
        }
    }

    // ── Agent health ──────────────────────────────────────────────────────────

    /**
     * GET /api/observability/agents[?providerAccountId=xxx]
     *
     * Hosts known to this account with a reporting status for each. Classification is done
     * server-side so the console and the agent-silence alerts can never disagree.
     */
    @GetMapping("/agents")
    public ResponseEntity<?> listAgents(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(required = false) String providerAccountId) {
        try {
            return ResponseEntity.ok(Map.of(
                    "agents", agentHealthService.listAgents(userDetails.getClientId(), providerAccountId)));
        } catch (Exception e) {
            return errorResponse("listAgents", e);
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
            return errorResponse("getOverview", e);
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
            return errorResponse("listStreams", e);
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
            return errorResponse("searchLogs", e);
        }
    }

    /**
     * POST /api/observability/logs/hosts
     * Body: { stream, startTime, endTime, providerAccountId? }
     * Returns the distinct host names sending logs — powers the host dropdown.
     */
    @PostMapping("/logs/hosts")
    public ResponseEntity<?> listLogHosts(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            return ResponseEntity.ok(Map.of(
                    "hosts", openObserveService.listLogHosts(userDetails.getClientId(), params)));
        } catch (Exception e) {
            return errorResponse("listLogHosts", e);
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
            return errorResponse("getLogHistogram", e);
        }
    }

    // ── Traces ────────────────────────────────────────────────────────────────

    /**
     * POST /api/observability/traces/search
     * Body: { stream?, keyword?, service?, minDurationMs?, errorsOnly?, startTime, endTime, size?, providerAccountId? }
     */
    @PostMapping("/traces/search")
    public ResponseEntity<?> searchTraces(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            return ResponseEntity.ok(openObserveService.searchTraces(userDetails.getClientId(), params));
        } catch (Exception e) {
            return errorResponse("searchTraces", e);
        }
    }

    /**
     * POST /api/observability/traces/stats
     * Body: { stream?, keyword?, service?, minDurationMs?, errorsOnly?, startTime, endTime, interval?, providerAccountId? }
     */
    @PostMapping("/traces/stats")
    public ResponseEntity<?> getTraceStats(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            return ResponseEntity.ok(openObserveService.getTraceStats(userDetails.getClientId(), params));
        } catch (Exception e) {
            return errorResponse("getTraceStats", e);
        }
    }

    /**
     * POST /api/observability/traces/spans
     * Body: { traceId, stream?, startTime?, endTime?, providerAccountId? }
     */
    @PostMapping("/traces/spans")
    public ResponseEntity<?> getTraceSpans(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestBody Map<String, Object> params) {
        try {
            return ResponseEntity.ok(openObserveService.getTraceSpans(userDetails.getClientId(), params));
        } catch (Exception e) {
            return errorResponse("getTraceSpans", e);
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
            return errorResponse("queryMetrics", e);
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
            return errorResponse("getClusters", e);
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
            return errorResponseText("getFluentBitConfig", e);
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
            return errorResponseText("getHelmValues", e);
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
            return errorResponseText("getOtelConfig", e);
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
            return errorResponseText("getHelmEksCommand", e);
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
            return errorResponseText("getEc2OtelConfig", e);
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
            return errorResponseText("getGceOtelConfig", e);
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
            return errorResponseText("getAgentSecret", e);
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
            return errorResponseText("getAgentManifest", e);
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
            return errorResponse("listAlerts", e);
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
            return errorResponse("createAlert", e);
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
            return errorResponse("deleteAlert", e);
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
            return errorResponse("toggleAlert", e);
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
            return errorResponse("listDestinations", e);
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
            return errorResponse("saveDestination", e);
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
            return errorResponse("deleteDestination", e);
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    /**
     * True when the failure is infrastructure (database pool, network to OpenObserve)
     * rather than something about the caller's request.
     */
    private static boolean isInfrastructureFailure(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof java.sql.SQLException
                    || t instanceof org.springframework.dao.DataAccessException
                    || t instanceof org.springframework.transaction.CannotCreateTransactionException
                    || t instanceof org.springframework.web.client.ResourceAccessException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            String cls = t.getClass().getName();
            if (cls.startsWith("org.hibernate.exception.") || cls.startsWith("com.zaxxer.hikari")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a client-safe error response.
     *
     * These endpoints previously returned {@code e.getMessage()} verbatim, which pushed raw
     * persistence internals into the browser — users saw
     * "Unable to acquire JDBC Connection; nested exception is org.hibernate.exception..."
     * which reveals internal stack details and tells them nothing actionable.
     *
     * Infrastructure failures now map to 503 with a retryable flag so the UI can show a
     * sensible "temporarily unavailable" state, while the full detail stays in the logs.
     */
    private ResponseEntity<Map<String, Object>> errorResponse(String operation, Exception e) {
        // Rejected input (bad stream name, non-SELECT query from the SQL editor). The message
        // is written for the user and is the only actionable thing we can tell them, so it is
        // returned as a 400 rather than being flattened into a generic 500.
        if (e instanceof IllegalArgumentException) {
            logger.debug("{} rejected invalid input: {}", operation, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Invalid request",
                    "retryable", false));
        }
        // Not-provisioned / no scoped ingestion credential. The message says what to do about
        // it, so it goes to the user as 409 rather than being flattened into a generic 500.
        if (e instanceof IllegalStateException) {
            logger.warn("{} could not complete: {}", operation, e.getMessage());
            return ResponseEntity.status(409).body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : "Not provisioned",
                    "retryable", false));
        }
        if (isInfrastructureFailure(e)) {
            logger.error("{} failed (infrastructure): {}", operation, e.toString());
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Observability data is temporarily unavailable. Please retry in a moment.",
                    "retryable", true));
        }
        logger.error("{} failed: {}", operation, e.getMessage());
        return ResponseEntity.internalServerError().body(Map.of(
                "error", e.getMessage() != null ? e.getMessage() : "Unexpected error",
                "retryable", false));
    }

    /**
     * Plain-text variant for the config/manifest generator endpoints.
     *
     * These render into a code block the user is about to copy, so a generic
     * "could not generate configuration" left them with nothing to act on. A refusal to issue
     * credentials is explained inline instead — it is the difference between "this product is
     * broken" and "provision this account first".
     */
    private ResponseEntity<String> errorResponseText(String operation, Exception e) {
        if (e instanceof IllegalStateException || e instanceof IllegalArgumentException) {
            logger.warn("{} could not complete: {}", operation, e.getMessage());
            return ResponseEntity.status(e instanceof IllegalStateException ? 409 : 400)
                    .body("# Configuration was not generated.\n#\n# " + e.getMessage());
        }
        if (isInfrastructureFailure(e)) {
            logger.error("{} failed (infrastructure): {}", operation, e.toString());
            return ResponseEntity.status(503)
                    .body("# Temporarily unavailable. Please retry in a moment.");
        }
        logger.error("{} failed: {}", operation, e.getMessage());
        return ResponseEntity.internalServerError().body("# Error: could not generate configuration");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Client-level (organisation-wide) config. {@code scope} lets the UI tell this apart from
     * an account-scoped response, which matters because the two can contain completely
     * different data for the same selected account.
     */
    private Map<String, Object> toConfigResponse(ObservabilityConfig config) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("orgId",         config.getO2OrgId() != null ? config.getO2OrgId() : "");
        m.put("retentionDays", config.getRetentionDays());
        m.put("provisionedAt", config.getCreatedAt() != null ? config.getCreatedAt().toString() : "");
        m.put("scope", "client");
        m.put("accountProvisioned", true);
        return m;
    }

    private Map<String, Object> toAccountConfigResponse(AccountObservabilityConfig accConfig,
                                                         ObservabilityConfig clientConfig) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("orgId",         accConfig.getO2OrgId() != null ? accConfig.getO2OrgId() : "");
        m.put("accountName",   accConfig.getAccountName() != null ? accConfig.getAccountName() : "");
        m.put("retentionDays", clientConfig.getRetentionDays());
        m.put("provisionedAt", accConfig.getCreatedAt() != null ? accConfig.getCreatedAt().toString() : "");
        m.put("scope", "account");
        m.put("accountProvisioned", true);
        return m;
    }
}
