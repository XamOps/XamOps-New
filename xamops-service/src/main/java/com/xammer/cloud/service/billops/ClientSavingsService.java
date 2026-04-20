package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClientSavingsService {

    @Autowired CloudAccountRepository cloudAccountRepository;
    @Autowired DailySpotSavingsRepository dailySpotSavingsRepository;
    @Autowired EbsVolumeActionRepository ebsVolumeActionRepository;
    @Autowired CachedDataRepository cachedDataRepository;
    @Autowired ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ClientSavingsDashboardDto getSavings(Long clientId, LocalDate startDate, LocalDate endDate) {
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);
        String clientName = accounts.isEmpty() ? "" : accounts.get(0).getClient().getName();

        Map<String, List<CloudAccount>> byProvider = accounts.stream()
            .collect(Collectors.groupingBy(a -> a.getProvider() == null ? "" : a.getProvider()));

        List<CloudAccount> awsAccounts   = byProvider.getOrDefault("AWS",   List.of());
        List<CloudAccount> gcpAccounts   = byProvider.getOrDefault("GCP",   List.of());
        List<CloudAccount> azureAccounts = byProvider.getOrDefault("Azure", List.of());

        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant   = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        double awsSpot   = sumSpotSavings(awsAccounts,   startDate, endDate);
        double gcpSpot   = sumSpotSavings(gcpAccounts,   startDate, endDate);
        double azureSpot = sumSpotSavings(azureAccounts, startDate, endDate);
        double awsEbs    = sumEbsSavings(awsAccounts, startInstant, endInstant);

        double awsRightsizing   = sumCachedSavings(awsAccounts,   "DASHBOARD_OPT::",      "totalMonthlySavings");
        double gcpRightsizing   = sumCachedSavings(gcpAccounts,   "GCP_RIGHTSIZING::",    "monthlySavings");
        double azureRightsizing = sumCachedSavings(azureAccounts, "AZURE_RIGHTSIZING::",  "estimatedMonthlySavings");

        double awsReservations   = sumCachedSavings(awsAccounts,   "RESERVATION_ANALYSIS::", "estimatedMonthlySavings");
        double gcpReservations   = sumCachedSavings(gcpAccounts,   "RESERVATION_PAGE::",     "totalMonthlySavings");
        double azureReservations = sumCachedSavings(azureAccounts, "RESERVATION_PAGE::",     "totalMonthlySavings");

        double realizedTotal  = awsSpot + gcpSpot + azureSpot + awsEbs;
        double potentialTotal = awsRightsizing + gcpRightsizing + azureRightsizing
                              + awsReservations + gcpReservations + azureReservations;

        return ClientSavingsDashboardDto.builder()
            .realizedSpotAws(awsSpot)
            .realizedSpotGcp(gcpSpot)
            .realizedSpotAzure(azureSpot)
            .realizedEbsAws(awsEbs)
            .potentialRightsizingAws(awsRightsizing)
            .potentialRightsizingGcp(gcpRightsizing)
            .potentialRightsizingAzure(azureRightsizing)
            .potentialReservationsAws(awsReservations)
            .potentialReservationsGcp(gcpReservations)
            .potentialReservationsAzure(azureReservations)
            .realizedTotal(realizedTotal)
            .potentialTotal(potentialTotal)
            .grandTotal(realizedTotal + potentialTotal)
            .timeSeries(buildTimeSeries(awsAccounts, gcpAccounts, azureAccounts, startDate, endDate, startInstant, endInstant))
            .startDate(startDate.toString())
            .endDate(endDate.toString())
            .clientName(clientName)
            .accountCount(accounts.size())
            .cacheRefreshedAt(findOldestCacheDate(accounts))
            .build();
    }

    private double sumSpotSavings(List<CloudAccount> accounts, LocalDate start, LocalDate end) {
        return accounts.stream()
            .mapToDouble(a -> dailySpotSavingsRepository
                .findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .stream().mapToDouble(r -> r.getAmountSaved() != null ? r.getAmountSaved() : 0.0).sum())
            .sum();
    }

    private double sumEbsSavings(List<CloudAccount> accounts, Instant start, Instant end) {
        return accounts.stream()
            .mapToDouble(a -> ebsVolumeActionRepository
                .findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", start, end)
                .stream().mapToDouble(r -> r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0).sum())
            .sum();
    }

    private double sumCachedSavings(List<CloudAccount> accounts, String keyPrefix, String savingsField) {
        return accounts.stream()
            .mapToDouble(a -> cachedDataRepository.findById(keyPrefix + a.getProviderAccountId())
                .map(cd -> extractSavingsFromJson(cd.getJsonData(), savingsField))
                .orElse(0.0))
            .sum();
    }

    private double extractSavingsFromJson(String json, String field) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                double sum = 0.0;
                for (JsonNode item : root) sum += item.path(field).asDouble(0.0);
                return sum;
            }
            return root.path(field).asDouble(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private List<ClientSavingsDashboardDto.DailyPoint> buildTimeSeries(
            List<CloudAccount> aws, List<CloudAccount> gcp, List<CloudAccount> azure,
            LocalDate start, LocalDate end, Instant startInstant, Instant endInstant) {

        Map<LocalDate, double[]> byDate = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            byDate.put(d, new double[]{0, 0, 0, 0});
        }

        for (CloudAccount a : aws) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[0] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : gcp) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[1] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : azure) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[2] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : aws) {
            ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", startInstant, endInstant)
                .forEach(r -> {
                    LocalDate d = r.getInitiatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    double[] arr = byDate.getOrDefault(d, new double[]{0, 0, 0, 0});
                    arr[3] += r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0;
                });
        }

        return byDate.entrySet().stream()
            .map(e -> new ClientSavingsDashboardDto.DailyPoint(
                e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
            .collect(Collectors.toList());
    }

    private String findOldestCacheDate(List<CloudAccount> accounts) {
        return accounts.stream()
            .flatMap(a -> List.of(
                "GCP_RIGHTSIZING::" + a.getProviderAccountId(),
                "AZURE_RIGHTSIZING::" + a.getProviderAccountId(),
                "DASHBOARD_OPT::" + a.getProviderAccountId(),
                "RESERVATION_ANALYSIS::" + a.getProviderAccountId(),
                "RESERVATION_PAGE::" + a.getProviderAccountId()
            ).stream())
            .map(key -> cachedDataRepository.findById(key))
            .filter(Optional::isPresent)
            .map(opt -> opt.get().getLastUpdated().toLocalDate())
            .min(LocalDate::compareTo)
            .map(LocalDate::toString)
            .orElse(null);
    }
}
