package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.CloudMapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/cloudmap")
public class CloudmapController {

    private static final Logger logger = LoggerFactory.getLogger(CloudmapController.class);

    private final CloudMapService cloudMapService;

    public CloudmapController(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    @GetMapping("/vpcs")
    public CompletableFuture<ResponseEntity<List<ResourceDto>>> getVpcs(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("Fetching VPCs for cloudmap - Account: {}, Force Refresh: {}", accountId, forceRefresh);

        return cloudMapService.getVpcListForCloudmap(accountId, forceRefresh)
                .thenApply(vpcs -> {
                    logger.info("Successfully fetched {} VPCs for account {}", vpcs.size(), accountId);
                    return ResponseEntity.ok(vpcs);
                })
                .exceptionally(ex -> {
                    logger.error("Failed to get VPC list for cloudmap for account {}: {}", accountId, ex.getMessage(), ex);
                    return ResponseEntity.status(500).body(List.of());
                });
    }

    @GetMapping("/graph")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getGraphData(
            @RequestParam String accountId,
            @RequestParam(required = false) String vpcId,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.info("Fetching graph data - Account: {}, VPC: {}, Region: {}, Force Refresh: {}",
                accountId, vpcId, region, forceRefresh);

        return cloudMapService.getGraphData(accountId, vpcId, region, forceRefresh)
                .thenApply(elements -> {
                    logger.info("Successfully fetched {} graph elements for account {} and VPC {}",
                            elements.size(), accountId, vpcId);
                    return ResponseEntity.ok(elements);
                })
                .exceptionally(ex -> {
                    logger.error("Failed to get graph data for account {} and VPC {}: {}",
                            accountId, vpcId, ex.getMessage(), ex);
                    return ResponseEntity.status(500).body(List.of());
                });
    }
}