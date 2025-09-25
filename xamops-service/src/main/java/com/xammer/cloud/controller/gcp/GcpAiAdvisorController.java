package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpAiRecommendationDto;
import com.xammer.cloud.service.gcp.GcpAiAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/ai-advisor")
public class GcpAiAdvisorController {

    private final GcpAiAdvisorService gcpAiAdvisorService;

    public GcpAiAdvisorController(GcpAiAdvisorService gcpAiAdvisorService) {
        this.gcpAiAdvisorService = gcpAiAdvisorService;
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<GcpAiRecommendationDto>>> getRecommendations(@RequestParam String accountId) {
        return gcpAiAdvisorService.getAiRecommendations(accountId)
                .thenApply(ResponseEntity::ok);
    }
}