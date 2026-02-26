package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.service.gcp.GcpAutoSpottingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gcp/autospotting")
@RequiredArgsConstructor
@Slf4j
public class GcpAutoSpottingController {

    private final GcpAutoSpottingService gcpAutoSpottingService;

    @GetMapping("/migs/all-regions/{accountId}")
    public ResponseEntity<?> listMigsAllRegions(@PathVariable("accountId") String accountId) {
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
    public ResponseEntity<?> getSpotInstances(@PathVariable String accountId) {
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
            @RequestParam(defaultValue = "last7d") String range) {
        log.info("GcpAutoSpottingController.getHistory called with accountId={}, range={}", accountId, range);
        try {
            HistoryResponse history = gcpAutoSpottingService.getHistoryData(accountId, range);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Failed to get history: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/events/{accountId}")
    public ResponseEntity<?> getEvents(
            @PathVariable String accountId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String asgName) {
        log.info("GcpAutoSpottingController.getEvents called with accountId={}, start={}, end={}, eventType={}, asgName={}",
                accountId, start, end, eventType, asgName);
        try {
            com.xammer.cloud.dto.autospotting.EventsResponse events = 
                gcpAutoSpottingService.getEvents(accountId, start, end, eventType, asgName);
            return ResponseEntity.ok(events);
        } catch (Exception e) {
            log.error("Failed to get events: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/enable")
    public ResponseEntity<?> enableAutoSpotting(
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName) {
        log.info("🚀 [GCP DEBUG] enableAutoSpotting called - accountId: {}, region: {}, migName: {}", accountId, region,
                migName);
        try {
            gcpAutoSpottingService.enableAutoSpotting(accountId, region, migName);
            log.info("✅ [GCP DEBUG] Successfully enabled Spot for MIG: {}", migName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting enabled for MIG " + migName));
        } catch (Exception e) {
            log.error("❌ [GCP DEBUG] Failed to enable AutoSpotting for {}: {}", migName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }

    @PostMapping("/disable")
    public ResponseEntity<?> disableAutoSpotting(
            @RequestParam("accountId") String accountId,
            @RequestParam("region") String region,
            @RequestParam("migName") String migName) {
        log.info("🚀 [GCP DEBUG] disableAutoSpotting called - accountId: {}, region: {}, migName: {}", accountId,
                region, migName);
        try {
            gcpAutoSpottingService.disableAutoSpotting(accountId, region, migName);
            log.info("✅ [GCP DEBUG] Successfully disabled Spot for MIG: {}", migName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "AutoSpotting disabled for MIG " + migName));
        } catch (Exception e) {
            log.error("❌ [GCP DEBUG] Failed to disable AutoSpotting for {}: {}", migName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage()));
        }
    }
}
