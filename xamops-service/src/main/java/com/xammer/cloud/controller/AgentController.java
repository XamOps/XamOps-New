package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.EksAutoscalingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for XamOps Agent communication.
 *
 * Endpoints:
 * - POST /api/v1/agent/ingest - Receive pushed data from agent
 * - POST /api/v1/agent/heartbeat - Agent heartbeat (check-in without data)
 * - GET /api/v1/agent/status - Get agent status (for dashboard)
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);

    private final AgentIngestService agentIngestService;
    private final DbAgentService dbAgentService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;

    public AgentController(
            AgentIngestService agentIngestService,
            DbAgentService dbAgentService,
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository) {
        this.agentIngestService = agentIngestService;
        this.dbAgentService = dbAgentService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
    }

    /**
     * Extract API key from Authorization header.
     * Format: "Bearer <api_key>"
     */
    private String extractApiKey(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7).trim();
    }

    /**
     * Get client IP address from request.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Main ingest endpoint - receives data pushed by agent.
     *
     * All agents use the SAME global API key (configured in application.properties).
     * Agents identify themselves by clusterName + cloudAccountId in the payload.
     *
     * POST /api/v1/agent/ingest
     * Authorization: Bearer <global_api_key>
     * Content-Type: application/json
     */
    @PostMapping("/ingest")
    public ResponseEntity<AgentIngestResponse> ingestData(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody AgentIngestPayload payload,
            HttpServletRequest request) {

        // 1. Validate global API key
        String apiKey = extractApiKey(authHeader);
        if (apiKey == null) {
            logger.warn("Agent ingest request missing authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AgentIngestResponse.error("Missing authorization header"));
        }

        if (!agentIngestService.validateApiKey(apiKey)) {
            logger.warn("Agent ingest request with invalid API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AgentIngestResponse.error("Invalid API key"));
        }

        // 2. Validate required payload fields
        if (payload.getClusterName() == null || payload.getClusterName().isEmpty()) {
            logger.warn("Agent ingest request missing clusterName");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AgentIngestResponse.error("clusterName is required in payload"));
        }

        if (payload.getCloudAccountId() == null || payload.getCloudAccountId().isEmpty()) {
            logger.warn("Agent ingest request missing cloudAccountId");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(AgentIngestResponse.error("cloudAccountId is required in payload"));
        }

        String clientIp = getClientIp(request);

        // 3. Check DB agent registry first — DB agents are pre-registered via the wizard
        java.util.Optional<DbAgentConfig> dbAgent = dbAgentService.findDbAgent(
                payload.getCloudAccountId(), payload.getClusterName());
        if (dbAgent.isPresent()) {
            AgentIngestResponse response = dbAgentService.processIngest(dbAgent.get(), payload, clientIp);
            logger.info("DB agent ingest completed for agent: {} (batches={})",
                    dbAgent.get().getAgentName(), response.getResults().size());
            return ResponseEntity.ok(response);
        }

        // 4. Fall through to K8s cluster agent path
        KubernetesClusterConfig clusterConfig = agentIngestService.findClusterConfig(
                payload.getClusterName(), payload.getCloudAccountId());

        if (clusterConfig == null) {
            logger.warn("No DB agent or K8s cluster config found for cluster: {} accountId: {}",
                    payload.getClusterName(), payload.getCloudAccountId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentIngestResponse.error("Agent not registered. Use the DbOps wizard to register this agent first."));
        }

        AgentIngestResponse response = agentIngestService.processIngest(clusterConfig, payload, clientIp);
        logger.info("K8s agent ingest completed for cluster: {} (success={}, batches={})",
                clusterConfig.getClusterName(), response.isSuccess(), response.getResults().size());

        return ResponseEntity.ok(response);
    }

    /**
     * Register a DB agent before deployment (called by the DbOps wizard).
     * Idempotent — safe to call multiple times.
     *
     * POST /api/v1/agent/db/register
     * Authorization: Bearer <global_api_key>
     * Body: { "agentName": "my-rds-agent", "cloudAccountId": "015764906357" }
     */
    @PostMapping("/db/register")
    public ResponseEntity<Map<String, Object>> registerDbAgent(
            @RequestBody Map<String, String> body) {

        String agentName = body.get("agentName");
        String cloudAccountId = body.get("cloudAccountId");

        if (agentName == null || agentName.isEmpty() || cloudAccountId == null || cloudAccountId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "agentName and cloudAccountId are required"));
        }

        DbAgentConfig cfg = dbAgentService.registerDbAgent(cloudAccountId, agentName);
        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cloud account not found. Add account to XamOps first."));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "agentId", cfg.getId(),
                "agentName", cfg.getAgentName(),
                "status", cfg.getStatus()
        ));
    }

    /**
     * Heartbeat endpoint - agent check-in without data.
     *
     * POST /api/v1/agent/heartbeat
     * Authorization: Bearer <global_api_key>
     * Body: { "clusterName": "xxx", "cloudAccountId": 123, "agentVersion": "1.0.0" }
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        // 1. Validate global API key
        String apiKey = extractApiKey(authHeader);
        if (apiKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing authorization header"));
        }

        if (!agentIngestService.validateApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid API key"));
        }

        // 2. Get cluster info from body
        if (body == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Request body required with clusterName and cloudAccountId"));
        }

        String clusterName = (String) body.get("clusterName");
        String cloudAccountId = body.get("cloudAccountId") != null ? body.get("cloudAccountId").toString() : null;

        if (clusterName == null || cloudAccountId == null || cloudAccountId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "clusterName and cloudAccountId are required"));
        }

        String agentVersion = (String) body.get("agentVersion");
        String clientIp = getClientIp(request);

        // 3. Check DB agent registry first
        java.util.Optional<DbAgentConfig> dbAgent = dbAgentService.findDbAgent(cloudAccountId, clusterName);
        if (dbAgent.isPresent()) {
            dbAgentService.recordHeartbeat(dbAgent.get(), agentVersion, clientIp);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "agentName", dbAgent.get().getAgentName(),
                    "agentType", "db",
                    "serverTime", LocalDateTime.now().toString()
            ));
        }

        // 4. Fall through to K8s cluster agent path
        KubernetesClusterConfig clusterConfig = agentIngestService.findClusterConfig(clusterName, cloudAccountId);
        if (clusterConfig == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Agent not registered"));
        }

        clusterConfig.setLastAgentCheckin(LocalDateTime.now());
        if (agentVersion != null) clusterConfig.setAgentVersion(agentVersion);
        clusterConfig.setAgentStatus("active");
        clusterConfigRepository.save(clusterConfig);

        AgentEvent event = new AgentEvent(clusterConfig.getId(), "checkin");
        event.setAgentVersion(agentVersion);
        event.setAgentIp(clientIp);
        agentEventRepository.save(event);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "clusterName", clusterConfig.getClusterName(),
                "serverTime", LocalDateTime.now().toString()
        ));
    }

    /**
     * Get agent status for a cluster (used by dashboard).
     *
     * GET /api/v1/agent/status/{clusterConfigId}
     */
    @GetMapping("/status/{clusterConfigId}")
    public ResponseEntity<Map<String, Object>> getAgentStatus(@PathVariable Long clusterConfigId) {
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findById(clusterConfigId);
        if (configOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        KubernetesClusterConfig config = configOpt.get();
        Map<String, Object> status = new HashMap<>();

        status.put("clusterName", config.getClusterName());
        status.put("agentStatus", config.getAgentStatus());
        status.put("agentVersion", config.getAgentVersion());
        status.put("lastCheckin", config.getLastAgentCheckin());
        status.put("isActive", config.isAgentActive());
        status.put("dataSourceMode", config.getDataSourceMode());

        // Get data counts
        List<AgentDataCache> cachedData = agentDataCacheRepository.findByClusterConfigId(clusterConfigId);
        Map<String, Object> dataCounts = new HashMap<>();
        for (AgentDataCache cache : cachedData) {
            dataCounts.put(cache.getDataType(), Map.of(
                    "itemCount", cache.getItemCount(),
                    "collectedAt", cache.getCollectedAt()
            ));
        }
        status.put("cachedData", dataCounts);

        // Get recent events
        List<AgentEvent> recentEvents = agentEventRepository.findRecentEvents(
                clusterConfigId, LocalDateTime.now().minusHours(24));
        status.put("recentEventsCount", recentEvents.size());

        // Get last successful push
        List<AgentEvent> lastPush = agentEventRepository.findLastSuccessfulPush(
                clusterConfigId, PageRequest.of(0, 1));
        if (!lastPush.isEmpty()) {
            status.put("lastSuccessfulPush", lastPush.get(0).getCreatedAt());
            status.put("lastPushItemCount", lastPush.get(0).getItemsReceived());
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Get agent status by cluster name and account ID (used by EKS dashboard).
     *
     * GET /api/v1/agent/status?clusterName=xxx&cloudAccountId=xxx
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getAgentStatusByCluster(
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId) {

        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);

        if (configOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "clusterName", clusterName,
                    "agentStatus", "not_configured",
                    "isActive", false
            ));
        }

        return getAgentStatus(configOpt.get().getId());
    }

    /**
     * GET /api/v1/agent/install-info
     * Returns pre-filled values needed to generate the agent install command in the UI.
     * Called by the frontend "Agent Stats" tab to build the one-liner.
     */
    @GetMapping("/install-info")
    public ResponseEntity<Map<String, String>> getInstallInfo() {
        return ResponseEntity.ok(Map.of(
                "apiKey", agentIngestService.getGlobalApiKey()
        ));
    }

    /**
     * POST /api/v1/agent/db/config
     * Save connection configuration from the wizard (Step 2).
     */
    @PostMapping("/db/config")
    public ResponseEntity<Map<String, Object>> saveDbAgentConfig(
            @RequestBody Map<String, String> body) {

        String agentName     = body.get("agentName");
        String cloudAccountId = body.get("cloudAccountId");
        if (agentName == null || cloudAccountId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentName and cloudAccountId are required"));
        }

        DbAgentConfig cfg = dbAgentService.saveConnectionConfig(
                cloudAccountId, agentName,
                body.get("dbEngine"), body.get("dbHost"), body.get("dbPort"),
                body.get("dbName"), body.get("dbUser"), body.get("dbPassword"),
                body.get("serverUrl"));

        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cloud account not found"));
        }

        return ResponseEntity.ok(Map.of("success", true, "agentId", cfg.getId()));
    }

    /**
     * GET /api/v1/agent/db/config?agentName=xxx&cloudAccountId=xxx
     * Load saved connection configuration — pre-fills the wizard fields.
     */
    @GetMapping("/db/config")
    public ResponseEntity<Map<String, Object>> loadDbAgentConfig(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId) {

        DbAgentConfig cfg = dbAgentService.loadConnectionConfig(cloudAccountId, agentName);
        if (cfg == null) {
            return ResponseEntity.ok(Map.of("found", false));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("found", true);
        result.put("dbEngine",  cfg.getDbEngine()   != null ? cfg.getDbEngine()   : "");
        result.put("dbHost",    cfg.getDbHost()     != null ? cfg.getDbHost()     : "");
        result.put("dbPort",    cfg.getDbPort()     != null ? cfg.getDbPort()     : "");
        result.put("dbName",    cfg.getDbName()     != null ? cfg.getDbName()     : "");
        result.put("dbUser",    cfg.getDbUser()     != null ? cfg.getDbUser()     : "");
        result.put("dbPassword",cfg.getDbPassword() != null ? cfg.getDbPassword() : "");
        result.put("serverUrl", cfg.getServerUrl()  != null ? cfg.getServerUrl()  : "");
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/agent/db/status?agentName=xxx&cloudAccountId=xxx
     * Returns live status of a registered DB agent — used by the wizard polling.
     */
    @GetMapping("/db/status")
    public ResponseEntity<Map<String, Object>> getDbAgentStatus(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId) {

        java.util.Optional<DbAgentConfig> cfgOpt = dbAgentService.findDbAgent(cloudAccountId, agentName);
        if (cfgOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "registered", false,
                    "status", "not_registered"
            ));
        }

        DbAgentConfig cfg = cfgOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("registered", true);
        result.put("status", cfg.getStatus());
        result.put("agentVersion", cfg.getAgentVersion() != null ? cfg.getAgentVersion() : "");
        result.put("agentIp", cfg.getAgentIp() != null ? cfg.getAgentIp() : "");
        result.put("lastCheckin", cfg.getLastCheckin() != null ? cfg.getLastCheckin().toString() : null);
        result.put("isActive", cfg.isActive());

        // Data counts from db_agent_data_cache
        List<com.xammer.cloud.domain.DbAgentDataCache> data = dbAgentService.getCachedData(cfg.getId());
        Map<String, Object> dataCounts = new HashMap<>();
        for (com.xammer.cloud.domain.DbAgentDataCache cache : data) {
            dataCounts.put(cache.getDataType(), Map.of(
                    "itemCount", cache.getItemCount() != null ? cache.getItemCount() : 0,
                    "collectedAt", cache.getCollectedAt().toString()
            ));
        }
        result.put("dataCounts", dataCounts);

        return ResponseEntity.ok(result);
    }

    /**
     * Get agent events for a cluster.
     *
     * GET /api/v1/agent/events/{clusterConfigId}
     */
    @GetMapping("/events/{clusterConfigId}")
    public ResponseEntity<List<AgentEvent>> getAgentEvents(
            @PathVariable Long clusterConfigId,
            @RequestParam(defaultValue = "50") int limit) {

        List<AgentEvent> events = agentEventRepository.findByClusterConfigIdOrderByCreatedAtDesc(
                clusterConfigId, PageRequest.of(0, limit)).getContent();

        return ResponseEntity.ok(events);
    }

    // =========================================================================
    // AUTOSCALING CONTROLLER ENDPOINTS
    // Used by xamops-autoscaler agent (authenticated with same global API key)
    // =========================================================================

    /**
     * GET /api/v1/agent/autoscaling-policy?clusterName=xxx&cloudAccountId=xxx
     *
     * Called by xamops-autoscaler on every policy poll cycle.
     * Returns the PolicyBundle JSON (from agent_data_cache dataType="autoscaling-policy")
     * so the controller knows the current user-configured autoscaling settings.
     *
     * Returns 404 if autoscaling is not enabled for this cluster.
     * Returns 200 with PolicyBundle JSON if enabled.
     */
    @GetMapping("/autoscaling-policy")
    public ResponseEntity<?> getAutoscalingPolicy(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId) {

        // Validate API key
        String apiKey = extractApiKey(authHeader);
        if (apiKey == null || !agentIngestService.validateApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing API key"));
        }

        // Find cluster config
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository
                .findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);

        if (configOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cluster not found or not configured"));
        }

        KubernetesClusterConfig config = configOpt.get();

        // Guard: autoscaling must be enabled for this cluster
        if (!Boolean.TRUE.equals(config.getAutoscalingEnabled())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Autoscaling not enabled for this cluster"));
        }

        // Read policy bundle from agent_data_cache
        Optional<AgentDataCache> policyCache = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(config.getId(), EksAutoscalingService.DT_POLICY);

        if (policyCache.isEmpty()) {
            // No policy saved yet — return empty 200 so autoscaler uses its own defaults
            return ResponseEntity.ok(Map.of("autoscalerMode", "full"));
        }

        // Return raw JSON string (autoscaler parses it as PolicyBundle)
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(policyCache.get().getDataJson());
    }
}
