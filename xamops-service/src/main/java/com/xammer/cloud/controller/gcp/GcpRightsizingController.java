package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/rightsizing")
public class GcpRightsizingController {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpRightsizingController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<GcpOptimizationRecommendation>>> getRightsizingRecommendations(@RequestParam String accountId) {
        return gcpOptimizationService.getRightsizingRecommendations(accountId)
                .thenApply(ResponseEntity::ok);
    }
}