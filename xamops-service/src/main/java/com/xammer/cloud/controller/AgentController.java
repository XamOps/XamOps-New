package com.xammer.cloud.controller;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.agent.AgentIngestPayload;
import com.xammer.cloud.dto.agent.AgentIngestResponse;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.autoscaling.AutomationPolicyBundle;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ExplainRequestRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentApiKeyService;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.DbAgentService;
import com.xammer.cloud.service.DiskAgentPeaks;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.K8sAutomationPolicyNormalizer;
import com.xammer.cloud.service.OpenObserveService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.sre.SreInvestigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /** Redis TTL for agent-reported disk data (6 hours — agent collects every 5 min). */
    private static final int DISK_AGENT_TTL_MINUTES = 360;
    /** Redis key prefix: ebs-disk-agent:{cloudAccountId}:{instanceId} (AWS) */
    static final String DISK_AGENT_KEY_PREFIX = "ebs-disk-agent:";
    /** Redis key prefix: gcp-disk-agent:{projectId}:{instanceName} (GCP) */
    static final String GCP_DISK_AGENT_KEY_PREFIX = "gcp-disk-agent:";
    /** Redis key prefix: azure-disk-agent:{cloudAccountId}:{vmName} (Azure) */
    static final String AZURE_DISK_AGENT_KEY_PREFIX = "azure-disk-agent:";
    /** Sentinel prefix for Azure disk agents. */
    static final String AZURE_DISK_AGENT_SEEN_PREFIX = "azure-disk-agent-seen:";
    /** Sentinel key written on every push; 24-hour TTL survives brief collection gaps. */
    static final String DISK_AGENT_SEEN_PREFIX = "ebs-disk-agent-seen:";
    static final String GCP_DISK_AGENT_SEEN_PREFIX = "gcp-disk-agent-seen:";

    // Rolling filesystem-utilization high-water marks; see DiskAgentPeaks for why they exist.
    private static final int DISK_AGENT_SEEN_TTL_MINUTES = 1440; // 24 hours

    private static final int SRE_AUTO_COOLDOWN_MINUTES = 30;

    private final AgentIngestService agentIngestService;
    private final DbAgentService dbAgentService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final RedisCacheService redisCacheService;
    private final CloudAccountRepository cloudAccountRepository;
    private final SreInvestigationService sreInvestigationService;
    private final OpenObserveService openObserveService;
    private final ExplainRequestRepository explainRequestRepository;
    private final AgentApiKeyService agentApiKeyService;
    private final K8sAutomationPolicyNormalizer policyNormalizer;
    private final K8sAutomationAccessService automationAccessService;

    public AgentController(
            AgentIngestService agentIngestService,
            DbAgentService dbAgentService,
            KubernetesClusterConfigRepository clusterConfigRepository,
            AgentDataCacheRepository agentDataCacheRepository,
            AgentEventRepository agentEventRepository,
            RedisCacheService redisCacheService,
            CloudAccountRepository cloudAccountRepository,
            SreInvestigationService sreInvestigationService,
            OpenObserveService openObserveService,
            ExplainRequestRepository explainRequestRepository,
            AgentApiKeyService agentApiKeyService,
            K8sAutomationPolicyNormalizer policyNormalizer,
            K8sAutomationAccessService automationAccessService) {
        this.agentApiKeyService = agentApiKeyService;
        this.policyNormalizer = policyNormalizer;
        this.automationAccessService = automationAccessService;
        this.agentIngestService = agentIngestService;
        this.dbAgentService = dbAgentService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.redisCacheService = redisCacheService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.sreInvestigationService = sreInvestigationService;
        this.openObserveService = openObserveService;
        this.explainRequestRepository = explainRequestRepository;
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

        String clientIp = getClientIp(request);

        // 1. Authenticate, resolving which account/cluster this key actually belongs to.
        String apiKey = extractApiKey(authHeader);
        if (apiKey == null) {
            logger.warn("Agent ingest request missing authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(AgentIngestResponse.error("Missing authorization header"));
        }

        AgentPrincipal principal = agentIngestService.authenticate(apiKey, clientIp);
        if (principal == null) {
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

        // 2b. The payload states which cluster and account it is for, and every handler below
        // trusts that. Confirm the key is entitled to it — without this, any key holder can
        // write into another tenant's data, and the shared key is published in the installers.
        String denied = agentIngestService.checkOwnership(
                principal, payload.getCloudAccountId(), payload.getClusterName());
        if (denied != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AgentIngestResponse.error(denied));
        }

        // 3a. Handle disk-usage batches — store in Redis keyed by instanceId, no cluster config needed
        boolean isDiskPush = payload.getBatches() != null && payload.getBatches().stream()
                .anyMatch(b -> "disk-usage".equals(b.getCollector()));
        if (isDiskPush) {
            handleDiskAgentPush(payload);
            return ResponseEntity.ok(AgentIngestResponse.success());
        }

        // 3b-sre. Handle sre-alerts batches — auto-trigger investigations for detected anomalies
        if (payload.getBatches() != null) {
            payload.getBatches().stream()
                    .filter(b -> "sre-alerts".equals(b.getCollector()))
                    .findFirst()
                    .ifPresent(b -> handleSreAlertsBatch(b, payload.getCloudAccountId()));
        }

        // 3b. Check DB agent registry — auto-register on first push if payload contains DB batches
        java.util.Optional<DbAgentConfig> dbAgent = dbAgentService.findDbAgent(
                payload.getCloudAccountId(), payload.getClusterName());

        boolean isDbPush = payload.getBatches() != null && payload.getBatches().stream()
                .anyMatch(b -> b.getCollector() != null && b.getCollector().startsWith("db-"));

        if (dbAgent.isEmpty() && isDbPush) {
            // Auto-register DB agent on first push (supports agents deployed without going through wizard)
            logger.info("Auto-registering DB agent on first push: {} for account {}",
                    payload.getClusterName(), payload.getCloudAccountId());
            DbAgentConfig autoRegistered = dbAgentService.registerDbAgent(
                    payload.getCloudAccountId(), payload.getClusterName());
            dbAgent = java.util.Optional.of(autoRegistered);
        }

        if (dbAgent.isPresent()) {
            AgentIngestResponse response = dbAgentService.processIngest(dbAgent.get(), payload, clientIp);
            logger.info("DB agent ingest completed for agent: {} (batches={})",
                    dbAgent.get().getAgentName(), response.getResults().size());
            return ResponseEntity.ok(response);
        }

        // 4. Fall through to K8s cluster agent path. Azure clusters auto-register on
        // first push — AKS has no other registration path (see findOrAutoRegisterClusterConfig).
        KubernetesClusterConfig clusterConfig = agentIngestService.findOrAutoRegisterClusterConfig(
                payload.getClusterName(), payload.getCloudAccountId());

        if (clusterConfig == null) {
            logger.warn("No DB agent or K8s cluster config found for cluster: {} accountId: {}",
                    payload.getClusterName(), payload.getCloudAccountId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(AgentIngestResponse.error(
                            "Agent not registered. Register the cluster in XamOps before the agent pushes data, "
                                    + "and check that cloudAccountId matches an onboarded cloud account."));
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
     * DELETE /api/v1/agent/db/register?agentName=xxx&cloudAccountId=xxx
     * Deregisters a DB agent and deletes all its cached data.
     */
    @DeleteMapping("/db/register")
    public ResponseEntity<Map<String, Object>> deleteDbAgent(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId) {

        boolean deleted = dbAgentService.deleteDbAgent(cloudAccountId, agentName);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Agent not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
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

        String clientIp = getClientIp(request);

        // 1. Authenticate
        String apiKey = extractApiKey(authHeader);
        if (apiKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing authorization header"));
        }

        AgentPrincipal principal = agentIngestService.authenticate(apiKey, clientIp);
        if (principal == null) {
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

        // A heartbeat updates another agent's status record, so the same ownership check applies.
        String denied = agentIngestService.checkOwnership(principal, cloudAccountId, clusterName);
        if (denied != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", denied));
        }

        String agentVersion = (String) body.get("agentVersion");

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

        // 4. Fall through to K8s cluster agent path (auto-registers Azure clusters)
        KubernetesClusterConfig clusterConfig = agentIngestService.findOrAutoRegisterClusterConfig(
                clusterName, cloudAccountId);
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
     * GET /api/v1/agent/install-info?accountId=xxx
     *
     * Values the console needs to render an install command. Called by the logged-in frontend,
     * so it requires a session and mints the caller's own account-scoped key.
     *
     * It previously performed no authentication at all and returned the shared global key to any
     * caller — and it sits under the {@code /api/v1/agent/**} permitAll block, so that was an
     * open endpoint handing out the credential every agent authenticates with.
     */
    @GetMapping("/install-info")
    public ResponseEntity<Map<String, String>> getInstallInfo(
            @AuthenticationPrincipal ClientUserDetails userDetails,
            @RequestParam(value = "accountId", required = false) String accountId) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Sign in to view agent install details"));
        }

        // No account given: the caller wants a command they can fill in themselves. Return the
        // scoped key only when we know which account it is for — a key is meaningless, and
        // dangerous, without that binding.
        if (accountId == null || accountId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "accountId is required so the install command can be issued a "
                            + "key scoped to that account"));
        }

        CloudAccount account = cloudAccountRepository.findByProviderAccountId(accountId).orElse(null);
        if (account == null || account.getClient() == null
                || !account.getClient().getId().equals(userDetails.getClientId())) {
            // Same response whether the account is absent or belongs to someone else, so this
            // cannot be used to discover which account ids exist.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cloud account not found"));
        }

        return ResponseEntity.ok(Map.of(
                "apiKey", agentApiKeyService.getOrCreate(account.getId()),
                "accountId", accountId
        ));
    }

    /**
     * GET /api/v1/agent/config
     * Returns server-side config the agent needs at runtime (OpenObserve credentials).
     * The agent calls this on startup so operators never need to pass credentials manually.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAgentConfig(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "accountId", required = false) String accountId,
            HttpServletRequest request) {
        String apiKey = extractApiKey(authHeader);
        AgentPrincipal principal = apiKey == null
                ? null
                : agentIngestService.authenticate(apiKey, getClientIp(request));
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // `accountId` selects whose observability credential is returned, and the only previous
        // guard was the shared key — which is published in the install scripts and the frontend
        // bundle. Anyone could therefore enumerate account ids and collect every tenant's
        // ingestion credential. A scoped key may now only ask about its own account.
        String denied = agentIngestService.checkOwnership(principal, accountId, null);
        if (denied != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", denied));
        }

        // A scoped key knows its own account, so it never needs to be asked for one.
        String effectiveAccountId = principal.isScoped() ? principal.getProviderAccountId() : accountId;

        Map<String, Object> response = new HashMap<>();
        try {
            response.put("openobserve", openObserveService.getAgentConfig(effectiveAccountId));
        } catch (IllegalStateException e) {
            // No org-scoped ingestion credential for this account. Answering 409 leaves the
            // agent's OpenObserve settings unset, which disables log shipping — the correct
            // outcome. This used to answer with the OpenObserve root credential instead, which
            // gave every agent host read/write access to every tenant's telemetry.
            logger.warn("Refusing to issue agent OpenObserve config for account {}: {}",
                    effectiveAccountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/agent/commands?agentName=xxx
     * Polled by the DB agent to pick up the next pending command (e.g. EXPLAIN request).
     * Returns the oldest PENDING ExplainRequest for this agent and transitions it to IN_FLIGHT,
     * or returns an empty 204 when there is nothing to do.
     *
     * Authorization: Bearer <global_api_key>
     */
    @GetMapping("/commands")
    public ResponseEntity<?> pollCommands(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "agentName", required = false) String agentNameParam,
            @RequestHeader(value = "X-Agent-Name", required = false) String agentNameHeader) {

        String apiKey = extractApiKey(authHeader);
        if (apiKey == null || !agentIngestService.validateApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing API key"));
        }

        // NOTE: commands are addressed by agentName, which is not an account or cluster, so
        // there is nothing here to bind a scoped key against. Tightening this needs agentName
        // resolved back to its owning account first — tracked separately rather than left as a
        // check that looks like enforcement but is not.

        // The deployed agent sends its name in the X-Agent-Name header, never as a query
        // parameter. Declaring the parameter required meant every poll from every agent
        // raised MissingServletRequestParameterException, which the catch-all handler in
        // GlobalExceptionHandler reports as 500 — so a malformed request looked like a
        // server fault in the logs. Accept either form and reject a missing one as 400.
        String agentName = (agentNameParam != null && !agentNameParam.isBlank())
                ? agentNameParam
                : agentNameHeader;
        if (agentName == null || agentName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "agentName query parameter or X-Agent-Name header is required"));
        }

        var pending = explainRequestRepository
                .findFirstByAgentNameAndStatusOrderByCreatedAtAsc(agentName, "PENDING");
        if (pending.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        var req = pending.get();
        req.setStatus("IN_FLIGHT");
        explainRequestRepository.save(req);

        Map<String, Object> cmd = new HashMap<>();
        cmd.put("commandId",  req.getId());
        // The agent skips any command whose type is not exactly "explain".
        cmd.put("type",       "explain");
        cmd.put("dsnAlias",   req.getDsnAlias());
        cmd.put("queryText",  req.getQueryText());
        cmd.put("dbEngine",   req.getDbEngine());

        // Returned as a single-element array: the agent decodes the body into a slice, so a
        // bare object is discarded and the command is never executed.
        return ResponseEntity.ok(List.of(cmd));
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
    // Used by xamops-automation agent (authenticated with same global API key)
    // =========================================================================

    /**
     * GET /api/v1/agent/autoscaling-policy?clusterName=xxx&cloudAccountId=xxx
     *
     * Called by xamops-automation on every policy poll cycle.
     * Returns the complete, normalized AutomationPolicyBundle (K8sAutomationPolicyNormalizer.forAgent):
     * every section present, not-ready controllers served their safe values, and the disabled
     * default when the owning account isn't read-write and connected.
     *
     * Returns 404 if autoscaling is not enabled for this cluster.
     */
    @GetMapping("/autoscaling-policy")
    public ResponseEntity<?> getAutoscalingPolicy(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId) {

        // Validate API key
        String apiKey = extractApiKey(authHeader);
        AgentPrincipal principal = apiKey == null ? null : agentIngestService.authenticate(apiKey, null);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or missing API key"));
        }

        // cloudAccountId here is a Long that may have lost leading zeros; canAct normalises for
        // that. The autoscaling policy describes what the automation agent is allowed to change
        // in a cluster, so reading another tenant's is a disclosure worth refusing.
        String ownershipDenied = agentIngestService.checkOwnership(
                principal, String.valueOf(cloudAccountId), clusterName);
        if (ownershipDenied != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ownershipDenied));
        }

        // Find cluster config — three-tier lookup to handle all deployment patterns:
        // 1. By API key (most reliable — per-cluster key stored in agent_api_key column)
        // 2. By cloudAccountId Long directly (works when cloud_account_id IS the aws account number)
        // 3. By zero-padded AWS account ID via cloud_account table (handles leading-zero stripping:
        //    Long.parseLong("080310056620") = 80310056620, pad back to "080310056620" for DB lookup)
        Optional<KubernetesClusterConfig> configOpt = clusterConfigRepository.findByAgentApiKey(apiKey);
        if (configOpt.isEmpty()) {
            configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);
        }
        if (configOpt.isEmpty()) {
            String awsAccountIdPadded = String.format("%012d", cloudAccountId);
            List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountIdPadded);
            for (CloudAccount account : accounts) {
                configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(account.getId(), clusterName);
                if (configOpt.isPresent()) break;
            }
        }

        if (configOpt.isEmpty()) {
            logger.warn("autoscaling-policy: cluster not found — clusterName={} cloudAccountId={}", clusterName, cloudAccountId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cluster not found or not configured"));
        }

        KubernetesClusterConfig config = configOpt.get();

        // Guard: autoscaling must be enabled for this cluster
        if (!Boolean.TRUE.equals(config.getAutoscalingEnabled())) {
            logger.info("autoscaling-policy: autoscaling not enabled for cluster={}", config.getClusterName());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Autoscaling not enabled for this cluster"));
        }

        // Serve a complete, normalized bundle (design §6.6): never the raw stored JSON and never a
        // partial fallback, because older agent images zero any guard a bundle leaves out (E5).
        // Controllers that aren't ready are served their safe values whatever is stored, and an
        // owning account that isn't read-write and CONNECTED (or can't be found) gets the
        // complete default with autoscalerMode "disabled", so the agent idles (NIT 11).
        String storedPolicy = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(config.getId(), EksAutoscalingService.DT_POLICY)
                .map(AgentDataCache::getDataJson)
                .orElse(null);
        boolean ownerAllows = automationAccessService.agentPolicyBlock(config) == null;
        AutomationPolicyBundle bundle = policyNormalizer.forAgent(storedPolicy, config.getId(), ownerAllows);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(policyNormalizer.toJson(bundle));
    }

    // =========================================================================
    // DISK AGENT ENDPOINTS
    // =========================================================================

    /**
     * Handles disk-usage batches pushed by the XamOps disk agent.
     * Groups mounts by instanceId and stores each group in Redis so that
     * EbsDiskRightsizingService can look up actual disk utilisation without SSM.
     *
     * Redis key: ebs-disk-agent:{cloudAccountId}:{instanceId}
     * TTL: 6 hours (agent collects every 5 min — stale after 6h = agent died)
     */
    @SuppressWarnings("unchecked")
    private void handleSreAlertsBatch(AgentIngestPayload.DataBatch batch, String agentCloudAccountId) {
        if (batch.getItems() == null || batch.getItems().isEmpty()) return;

        // Resolve CloudAccount by agent's account identifier (AWS account ID, GCP project ID, or Azure subscription)
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(agentCloudAccountId);
        if (accounts.isEmpty()) {
            cloudAccountRepository.findByGcpProjectId(agentCloudAccountId).ifPresent(accounts::add);
        }
        if (accounts.isEmpty()) {
            cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(
                    agentCloudAccountId, agentCloudAccountId, agentCloudAccountId)
                    .ifPresent(accounts::add);
        }

        for (Object raw : batch.getItems()) {
            if (!(raw instanceof Map)) continue;
            Map<String, Object> alert = (Map<String, Object>) raw;

            String resourceId      = (String) alert.get("resourceId");
            String resourceName    = (String) alert.getOrDefault("resourceName", resourceId);
            String resourceTypeStr = (String) alert.getOrDefault("resourceType", "K8S_POD");
            String reason          = (String) alert.getOrDefault("reason", "");
            String message         = (String) alert.getOrDefault("message", reason);
            String namespace       = (String) alert.getOrDefault("namespace", "");

            if (resourceId == null || resourceId.isBlank()) continue;

            SreInvestigation.ResourceType resourceType;
            try {
                resourceType = SreInvestigation.ResourceType.valueOf(resourceTypeStr);
            } catch (IllegalArgumentException e) {
                resourceType = SreInvestigation.ResourceType.K8S_POD;
            }

            // Skip if an investigation for this resource was already created within cooldown window
            Instant cooldownSince = Instant.now().minusSeconds(SRE_AUTO_COOLDOWN_MINUTES * 60L);
            if (sreInvestigationService.hasRecentInvestigation(resourceId, cooldownSince)) {
                logger.debug("SRE auto-trigger skipped (cooldown) for resourceId={}", resourceId);
                continue;
            }

            if (accounts.isEmpty()) {
                logger.warn("SRE auto-trigger: no CloudAccount found for agentAccount={}", agentCloudAccountId);
                continue;
            }
            CloudAccount account = accounts.get(0);
            Long clientId = account.getClient().getId();

            TriggerInvestigationRequest req = new TriggerInvestigationRequest();
            req.setResourceType(resourceType);
            req.setResourceId(resourceId);
            req.setResourceName(resourceName);
            req.setCloudAccountId(account.getId());
            req.setAlertPayload("{\"reason\":\"" + reason
                    + "\",\"message\":\"" + message.replace("\"", "'")
                    + "\",\"namespace\":\"" + namespace + "\"}");

            logger.info("SRE auto-trigger: resourceId={} reason={} clientId={}", resourceId, reason, clientId);
            try {
                sreInvestigationService.createInvestigation(clientId, req, SreInvestigation.TriggerType.AUTO);
            } catch (Exception e) {
                logger.error("SRE auto-trigger failed for resourceId={}: {}", resourceId, e.getMessage());
            }
        }
    }

    private void handleDiskAgentPush(AgentIngestPayload payload) {
        String cloudAccountId = payload.getCloudAccountId();
        if (cloudAccountId == null) return;

        for (AgentIngestPayload.DataBatch batch : payload.getBatches()) {
            if (!"disk-usage".equals(batch.getCollector())) continue;
            if (batch.getItems() == null || batch.getItems().isEmpty()) continue;

            // Group mount entries by instanceId
            Map<String, List<Map<String, Object>>> byInstance = new HashMap<>();
            for (Object raw : batch.getItems()) {
                if (!(raw instanceof Map)) continue;
                Map<String, Object> mount = (Map<String, Object>) raw;
                String instanceId = (String) mount.get("instanceId");
                if (instanceId == null || instanceId.isEmpty()) continue;
                byInstance.computeIfAbsent(instanceId, k -> new ArrayList<>()).add(mount);
            }

            // Select key prefix based on cloud provider.
            //
            // Azure previously fell through to the AWS branch, so Azure agent pushes were
            // written under "ebs-disk-agent:" while AzureDiskRightsizingService read
            // "azure-disk-agent:". Nothing ever matched, which left the whole Azure disk
            // utilization feature (usedPercent, SHRINK/EXPAND) permanently inert.
            String provider = payload.getProvider() == null ? "AWS" : payload.getProvider().toUpperCase();
            String keyPrefix;
            String seenPrefix;
            switch (provider) {
                case "GCP":
                    keyPrefix = GCP_DISK_AGENT_KEY_PREFIX;
                    seenPrefix = GCP_DISK_AGENT_SEEN_PREFIX;
                    break;
                case "AZURE":
                    keyPrefix = AZURE_DISK_AGENT_KEY_PREFIX;
                    seenPrefix = AZURE_DISK_AGENT_SEEN_PREFIX;
                    break;
                default:
                    keyPrefix = DISK_AGENT_KEY_PREFIX;
                    seenPrefix = DISK_AGENT_SEEN_PREFIX;
                    break;
            }

            // Cache each instance's disk data in Redis
            for (Map.Entry<String, List<Map<String, Object>>> entry : byInstance.entrySet()) {
                String instanceId = entry.getKey();
                String cacheKey = keyPrefix + cloudAccountId + ":" + instanceId;
                redisCacheService.put(cacheKey, entry.getValue(), DISK_AGENT_TTL_MINUTES);
                // Sentinel key survives brief collection gaps (e.g. EBS resize / GCE disk resize)
                String seenKey = seenPrefix + cloudAccountId + ":" + instanceId;
                redisCacheService.put(seenKey, Boolean.TRUE, DISK_AGENT_SEEN_TTL_MINUTES);
                updateDiskUtilizationPeaks(keyPrefix, cloudAccountId, instanceId, entry.getValue());
                logger.info("[disk-agent] STORED key={} mounts={}", cacheKey, entry.getValue().size());
                logger.debug("[disk-agent] cached {} mounts for instance {} account {}",
                        entry.getValue().size(), instanceId, cloudAccountId);
            }
            logger.info("[disk-agent] stored disk data for {} instances (account {} provider={})",
                    byInstance.size(), cloudAccountId, provider);
        }
    }

    /**
     * Merges this push's utilization figures into the instance's high-water marks.
     *
     * <p>Deliberately best-effort: a failure here degrades the shrink recommendation to
     * "insufficient history", which is the safe outcome, so it must never fail an agent push.
     */
    private void updateDiskUtilizationPeaks(String keyPrefix, String cloudAccountId, String instanceId,
            List<Map<String, Object>> mounts) {
        try {
            String peakKey = DiskAgentPeaks.keyFor(keyPrefix, cloudAccountId, instanceId);
            Map<String, Object> existing = redisCacheService
                    .get(peakKey, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    .orElse(null);

            DiskAgentPeaks.merge(existing, mounts, System.currentTimeMillis())
                    .ifPresent(peaks -> redisCacheService.put(peakKey, peaks, DiskAgentPeaks.TTL_MINUTES));
        } catch (Exception e) {
            logger.debug("[disk-agent] could not update utilization peaks for {}: {}",
                    instanceId, e.getMessage());
        }
    }

}
