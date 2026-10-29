package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.DbAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Browser-facing DB-agent wizard endpoints (SEC-4/5).
 *
 * <p>
 * These moved off the unauthenticated {@code /api/v1/agent/**} path (where they took no
 * principal and the old {@code GET /db/config} returned the stored DB password in plaintext)
 * to this session-authenticated path. {@code /api/xamops/**} is not excluded by the Login-As
 * JWT chain's matcher, so BillOps "Login As" tabs still reach the wizard, while the main chain's
 * {@code .anyRequest().authenticated()} covers normal sessions. Every handler asserts account
 * entitlement before any read or write.
 */
@RestController
@RequestMapping("/api/xamops/db-agent")
public class DbAgentSetupController {

    private static final Logger logger = LoggerFactory.getLogger(DbAgentSetupController.class);

    private final CloudAccountAccessService accountAccessService;
    private final DbAgentService dbAgentService;

    public DbAgentSetupController(CloudAccountAccessService accountAccessService,
            DbAgentService dbAgentService) {
        this.accountAccessService = accountAccessService;
        this.dbAgentService = dbAgentService;
    }

    /**
     * Entitlement guard, run first in every handler. {@code requireAccessByIdentifier} closes
     * the cross-tenant IDOR (anonymous → 401 at the chain; not-entitled → 403 here). It carries
     * a numeric primary-key fallback, but {@code DbAgentService} addresses accounts by provider
     * id only, so the two must resolve to the same row — otherwise a bare PK (e.g.
     * {@code cloudAccountId=1}) that the caller happens to own would be mixed with a provider-id
     * lookup that finds nothing. Refuse that mismatch with 403, without leaking which ids exist.
     */
    private void requireEntitledAccount(ClientUserDetails userDetails, String cloudAccountId) {
        CloudAccount granted = accountAccessService.requireAccessByIdentifier(userDetails, cloudAccountId);
        CloudAccount resolved = dbAgentService.resolveAccount(cloudAccountId);
        if (resolved == null || !resolved.getId().equals(granted.getId())) {
            logger.warn("Blocked DB-agent setup: user={} identifier did not resolve consistently",
                    userDetails == null ? null : userDetails.getId());
            throw new AccessDeniedException("Account not found or access denied.");
        }
    }

    private static boolean hasPassword(DbAgentConfig cfg) {
        return cfg.getDbPassword() != null && !cfg.getDbPassword().isEmpty();
    }

    /**
     * POST /api/xamops/db-agent/register
     * Register a DB agent before deployment (called by the DbOps wizard). Idempotent.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerDbAgent(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        String agentName = body.get("agentName");
        String cloudAccountId = body.get("cloudAccountId");
        requireEntitledAccount(userDetails, cloudAccountId);

        if (agentName == null || agentName.isEmpty()) {
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
     * DELETE /api/xamops/db-agent/register?agentName=xxx&cloudAccountId=xxx
     * Deregisters a DB agent and deletes all its cached data.
     */
    @DeleteMapping("/register")
    public ResponseEntity<Map<String, Object>> deleteDbAgent(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireEntitledAccount(userDetails, cloudAccountId);

        boolean deleted = dbAgentService.deleteDbAgent(cloudAccountId, agentName);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Agent not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * POST /api/xamops/db-agent/config
     * Save connection configuration from the wizard (Step 2).
     *
     * <p>
     * SEC-5: the DB password is never persisted. {@code saveConnectionConfig} has no password
     * parameter, so any {@code dbPassword} in the request body is ignored — the agent reads its
     * DSN (password included) from its own environment on the customer host.
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveDbAgentConfig(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        String agentName = body.get("agentName");
        String cloudAccountId = body.get("cloudAccountId");
        requireEntitledAccount(userDetails, cloudAccountId);

        if (agentName == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentName and cloudAccountId are required"));
        }

        DbAgentConfig cfg = dbAgentService.saveConnectionConfig(
                cloudAccountId, agentName,
                body.get("dbEngine"), body.get("dbHost"), body.get("dbPort"),
                body.get("dbName"), body.get("dbUser"),
                body.get("serverUrl"));

        if (cfg == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Cloud account not found"));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "agentId", cfg.getId(),
                "hasPassword", hasPassword(cfg)
        ));
    }

    /**
     * GET /api/xamops/db-agent/config?agentName=xxx&cloudAccountId=xxx
     * Load saved connection configuration — pre-fills the wizard fields.
     *
     * <p>
     * SEC-5: the response carries a boolean {@code hasPassword} and NEVER a {@code dbPassword}
     * key or the password string. After the migration nulls stored passwords, {@code hasPassword}
     * is {@code false} for every row; it stays in the shape for API stability.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> loadDbAgentConfig(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireEntitledAccount(userDetails, cloudAccountId);

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
        result.put("hasPassword", hasPassword(cfg));
        result.put("serverUrl", cfg.getServerUrl()  != null ? cfg.getServerUrl()  : "");
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/xamops/db-agent/status?agentName=xxx&cloudAccountId=xxx
     * Returns live status of a registered DB agent — used by the wizard polling.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDbAgentStatus(
            @RequestParam String agentName,
            @RequestParam String cloudAccountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {

        requireEntitledAccount(userDetails, cloudAccountId);

        Optional<DbAgentConfig> cfgOpt = dbAgentService.findDbAgent(cloudAccountId, agentName);
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

        List<DbAgentDataCache> data = dbAgentService.getCachedData(cfg.getId());
        Map<String, Object> dataCounts = new HashMap<>();
        for (DbAgentDataCache cache : data) {
            dataCounts.put(cache.getDataType(), Map.of(
                    "itemCount", cache.getItemCount() != null ? cache.getItemCount() : 0,
                    "collectedAt", cache.getCollectedAt().toString()
            ));
        }
        result.put("dataCounts", dataCounts);

        return ResponseEntity.ok(result);
    }
}
