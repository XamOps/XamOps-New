package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.dto.gcp.GcpMigConfigDto;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.gcp.GcpAutoSpottingService;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gcp/autospotting")
@RequiredArgsConstructor
@Slf4j
public class GcpAutoSpottingController {

    private final GcpAutoSpottingService gcpAutoSpottingService;
    private final CloudAccountAccessService accountAccessService;
    private final AutoSpottingFeatureFlags featureFlags;

    /**
     * Typed refusals (paused conversions, GKE-managed pools, MIG update conflicts) as
     * {@code {success:false, code, error}}. Local, so it wins over the catch-all
     * {@code @ExceptionHandler(Exception.class)} in {@code GlobalExceptionHandler}.
     */
    @ExceptionHandler(AutoSpottingBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlocked(AutoSpottingBlockedException e) {
        log.info("GCP AutoSpotting action refused: {}", e.getCode());
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "success", false,
                "code", e.getCode(),
                "error", e.getMessage()));
    }

    @GetMapping("/migs/all-regions/{accountId}")
    public ResponseEntity<?> listMigsAllRegions(@PathVariable("accountId") String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("🚀 [GCP DEBUG] listMigsAllRegions called for accountId: {}", accountId);
        try {
            List<GcpAutoSpottingService.GcpAutoSpottingGroupDto> migs = gcpAutoSpottingService
                    .listMigsAllRegionsWithCosts(accountId);
            log.info("✅ [GCP DEBUG] Found {} MIGs for account: {}", migs.size(), accountId);
            return ResponseEntity.ok(migs);
        } catch (Exception e) {
            log.error("❌ [GCP DEBUG] Failed to list MIGs for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to list MIGs: " + e.getMessage()));
        }
    }

    @GetMapping("/instances/{accountId}")
    public ResponseEntity<?> getSpotInstances(@PathVariable String accountId,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("GcpAutoSpottingController.getSpotInstances called with accountId={}", accountId);
        try {
            List<SpotInstanceDto> instances = gcpAutoSpottingService.getSpotInstances(accountId);
            return ResponseEntity.ok(instances);
        } catch (Exception e) {
            log.error("Failed to list GCP spot instances: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch instances: " + e.getMessage()));
        }
    }

    @GetMapping("/history/{accountId}")
    public ResponseEntity<?> getHistory(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "last7d") String range,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("GcpAutoSpottingController.getHistory called with accountId={}, range={}", accountId, range);
        try {
            HistoryResponse history = gcpAutoSpottingService.getHistoryData(accountId, range);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            String histErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Failed to get history: {}", histErr, e);
            return ResponseEntity.status(500).body(Map.of("error", histErr));
        }
    }

    @GetMapping("/events/{accountId}")
    public ResponseEntity<?> getEvents(
            @PathVariable String accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String asgName,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("GcpAutoSpottingController.getEvents called with accountId={}, start={}, end={}, eventType={}, asgName={}",
                accountId, start, end, eventType, asgName);
        try {
            com.xammer.cloud.dto.autospotting.EventsResponse events = 
                gcpAutoSpottingService.getEvents(accountId, start, end, eventType, asgName);
            return ResponseEntity.ok(events);
        } catch (Exception e) {
            String evtErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Failed to get events: {}", evtErr, e);
            return ResponseEntity.status(500).body(Map.of("error", evtErr));
        }
    }

    @PostMapping("/enable")
    public ResponseEntity<?> enableAutoSpotting(
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        featureFlags.requireGcpConversionsEnabled();
        log.info("🚀 [GCP DEBUG] enableAutoSpotting called - accountId: {}, region: {}, migName: {}", accountId, region,
                migName);
        try {
            gcpAutoSpottingService.enableAutoSpotting(accountId, region, migName);
            log.info("✅ [GCP DEBUG] Successfully enabled Spot for MIG: {}", migName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting enabled for MIG " + migName));
        } catch (AutoSpottingBlockedException e) {
            throw e;
        } catch (Exception e) {
            String enableErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            log.error("❌ [GCP DEBUG] Failed to enable AutoSpotting for {}: {}", migName, enableErr, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", enableErr));
        }
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disableAutoSpotting(
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("🚀 [GCP DEBUG] disableAutoSpotting called - accountId: {}, region: {}, migName: {}", accountId,
                region, migName);
        try {
            gcpAutoSpottingService.disableAutoSpotting(accountId, region, migName);
            log.info("✅ [GCP DEBUG] Successfully disabled Spot for MIG: {}", migName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting disabled for MIG " + migName));
        } catch (AutoSpottingBlockedException e) {
            throw e;
        } catch (Exception e) {
            String disableErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            log.error("❌ [GCP DEBUG] Failed to disable AutoSpotting for {}: {}", migName, disableErr, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", disableErr));
        }
    }

    // ── MIG Configuration (mirrors AWS ASGConfig) ─────────────────────────────

    /**
     * GET /api/gcp/autospotting/migs/{accountId}/config?region=us-central1&migName=my-mig
     *
     * Returns the current update policy, autoscaler settings, machine type, and
     * Spot status for a specific MIG — equivalent to AWS /asgs/{id}/config.
     */
    @GetMapping("/migs/{accountId}/config")
    public ResponseEntity<?> getMigConfig(
            @PathVariable("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        log.info("GET /migs/{}/config  region={} migName={}", accountId, region, migName);
        try {
            GcpMigConfigDto config = gcpAutoSpottingService.getMigConfig(accountId, region, migName);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            String cfgErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            log.error("Failed to get MIG config for {}: {}", migName, cfgErr, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", cfgErr));
        }
    }

    /**
     * PUT /api/gcp/autospotting/migs/{accountId}/config?region=us-central1&migName=my-mig
     *
     * Updates the MIG update policy (maxSurge, maxUnavailable, minReadySeconds).
     * This controls how future Spot conversions roll out — zero-downtime by default.
     */
    @PutMapping("/migs/{accountId}/config")
    public ResponseEntity<?> updateMigConfig(
            @PathVariable("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName,
            @RequestBody GcpMigConfigDto config,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        accountAccessService.requireAccessByIdentifier(userDetails, accountId);
        featureFlags.requireGcpConversionsEnabled();
        log.info("PUT /migs/{}/config  region={} migName={} maxSurge={} maxUnavailable={} minReadySec={}",
                accountId, region, migName, config.getMaxSurge(), config.getMaxUnavailable(), config.getMinReadySeconds());
        try {
            GcpMigConfigDto updated = gcpAutoSpottingService.updateMigConfig(accountId, region, migName, config);
            return ResponseEntity.ok(updated);
        } catch (AutoSpottingBlockedException e) {
            throw e;
        } catch (Exception e) {
            String updErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            log.error("Failed to update MIG config for {}: {}", migName, updErr, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", updErr));
        }
    }
}
