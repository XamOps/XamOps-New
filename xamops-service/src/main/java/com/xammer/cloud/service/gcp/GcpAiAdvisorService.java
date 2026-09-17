package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.gcp.GcpAiRecommendationDto;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Surfaces GCP Recommender output as advisor recommendations.
 *
 * <p>This delegates to {@link GcpOptimizationService} rather than calling the Recommender API
 * itself. It previously kept its own copy of that call, and the copy carried three defects the
 * shared implementation does not:
 *
 * <ul>
 *   <li>It queried the VM machine-type recommender at location {@code "global"}. That recommender is
 *       zonal, so the request was well-formed but always returned an empty page — the endpoint
 *       reported "no recommendations" for every project, indefinitely.
 *   <li>It read savings as {@code getCost().getNanos() / -1e9}, ignoring {@code getUnits()}. Money
 *       carries whole currency units in {@code units} and only the fraction in {@code nanos}, so a
 *       $47/month saving was reported as $0.
 *   <li>It hardcoded the category to {@code COST_SAVING} and the action to "Consider changing machine
 *       type" for every item, and read {@code fieldsMap().get("resource")} without a null check.
 * </ul>
 *
 * <p>Delegating also means one cache, one dedup pass, and one set of recommenders to extend, instead
 * of two implementations that drift apart.
 */
@Service
@Slf4j
public class GcpAiAdvisorService {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpAiAdvisorService(@Lazy GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    public CompletableFuture<List<GcpAiRecommendationDto>> getAiRecommendations(String gcpProjectId) {
        return getAiRecommendations(gcpProjectId, false);
    }

    public CompletableFuture<List<GcpAiRecommendationDto>> getAiRecommendations(String gcpProjectId,
            boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<GcpOptimizationRecommendation> recommendations =
                        gcpOptimizationService.getRightsizingRecommendations(gcpProjectId, forceRefresh);

                if (recommendations == null || recommendations.isEmpty()) {
                    log.info("No GCP Recommender recommendations for project {}", gcpProjectId);
                    return new ArrayList<GcpAiRecommendationDto>();
                }

                List<GcpAiRecommendationDto> result = recommendations.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(this::toAdvisorDto)
                        .collect(Collectors.toList());

                log.info("Returning {} advisor recommendation(s) for project {}", result.size(), gcpProjectId);
                return result;
            } catch (Exception e) {
                log.error("Failed to get advisor recommendations for project {}", gcpProjectId, e);
                return new ArrayList<GcpAiRecommendationDto>();
            }
        });
    }

    /**
     * Maps a Recommender-derived rightsizing recommendation onto the advisor shape.
     *
     * <p>Category and action are both derived from the recommendation rather than fixed strings, so a
     * cost increase on an underprovisioned resource is no longer labelled a cost saving.
     */
    private GcpAiRecommendationDto toAdvisorDto(GcpOptimizationRecommendation rec) {
        GcpAiRecommendationDto dto = new GcpAiRecommendationDto();

        dto.setCategory(rec.isCostIncrease() ? "PERFORMANCE_IMPROVEMENT" : "COST_SAVING");
        dto.setResource(rec.getResourceName());

        String current = rec.getCurrentMachineType();
        String recommended = rec.getRecommendedMachineType();
        boolean haveBoth = current != null && !current.isBlank()
                && recommended != null && !recommended.isBlank();

        dto.setAction(haveBoth
                ? "Change " + serviceLabel(rec) + " machine type from " + current + " to " + recommended
                : "Review " + serviceLabel(rec) + " sizing for " + rec.getResourceName());

        String reason = rec.getReasonSummary();
        dto.setDescription(reason != null && !reason.isBlank()
                ? reason
                : dto.getAction());

        // A cost increase is not a saving. getAbsoluteMonthlyCost() carries the magnitude, and the
        // category above already says which direction it runs in.
        dto.setEstimatedMonthlySavings(rec.isCostIncrease() ? 0.0 : rec.getMonthlySavings());

        return dto;
    }

    private String serviceLabel(GcpOptimizationRecommendation rec) {
        String service = rec.getService();
        return service == null || service.isBlank() ? "resource" : service;
    }
}
