package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpCommittedUseDiscountDto;
import com.xammer.cloud.dto.gcp.GcpCudUtilizationDto;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/reservations")
public class GcpReservationController {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpReservationController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    @GetMapping("/cuds")
    public CompletableFuture<ResponseEntity<List<GcpCommittedUseDiscountDto>>> getCuds(@RequestParam String accountId) {
        return gcpOptimizationService.getCommittedUseDiscounts(accountId)
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/cud-utilization")
    public CompletableFuture<ResponseEntity<GcpCudUtilizationDto>> getCudUtilization(@RequestParam String accountId, @RequestParam String cudId) {
        return gcpOptimizationService.getCudUtilization(accountId, cudId)
                .thenApply(ResponseEntity::ok);
    }
}