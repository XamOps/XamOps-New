package com.xammer.cloud.service.gcp;

import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.xammer.cloud.dto.gcp.GcpAiRecommendationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpAiAdvisorService {

    private final GcpClientProvider gcpClientProvider;

    public GcpAiAdvisorService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public CompletableFuture<List<GcpAiRecommendationDto>> getAiRecommendations(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return new ArrayList<>();

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", "google.compute.instance.MachineTypeRecommender");
                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(this::mapToAiDto)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get AI recommendations for project {}", gcpProjectId, e);
                return new ArrayList<>();
            }
        });
    }
    
    private GcpAiRecommendationDto mapToAiDto(Recommendation rec) {
        GcpAiRecommendationDto dto = new GcpAiRecommendationDto();
        dto.setCategory("COST_SAVING");
        dto.setDescription(rec.getDescription());
        dto.setResource(rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue());
        dto.setAction("Consider changing machine type");
        dto.setEstimatedMonthlySavings(rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0);
        return dto;
    }
}