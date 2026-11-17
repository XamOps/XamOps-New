package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.dto.azure.AzureReservationData;
import com.xammer.cloud.dto.azure.AzureRightsizingRecommendation;
import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.azure.AzureRightsizingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

@Service
public class ClientSavingsService {

    private static final Logger log = LoggerFactory.getLogger(ClientSavingsService.class);

    private static final String CACHE_KEY_PREFIX = "CLIENT_SAVINGS::";
    private static final int CACHE_TTL_MINUTES = 60;

    // Snapshots behind the potential-savings columns, keyed <prefix><providerAccountId>. Each is
    // read with the DTO its writer saves, so a renamed field breaks the build instead of reading 0.
    private static final String AWS_RIGHTSIZING    = "DASHBOARD_OPT::";     // DashboardData
    private static final String AWS_RESERVATIONS   = "RESERVATION_PAGE::";  // ReservationDto
    private static final String GCP_RIGHTSIZING    = "GCP_RIGHTSIZING::";   // List<GcpOptimizationRecommendation>
    private static final String GCP_RESERVATIONS   = "GCP_CUD_RECS::";      // same, written by the GCP reservations page
    private static final String AZURE_RIGHTSIZING  = "AZURE_RIGHTSIZING::"; // AzureRightsizingResponse
    private static final String AZURE_RESERVATIONS = "RESERVATION_PAGE::";  // AzureReservationData

    @Autowired RedisCacheService redisCacheService;
    @Autowired CloudAccountRepository cloudAccountRepository;
    @Autowired DailySpotSavingsRepository dailySpotSavingsRepository;
    @Autowired EbsVolumeActionRepository ebsVolumeActionRepository;
    @Autowired CachedDataRepository cachedDataRepository;
    @Autowired ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ClientSavingsDashboardDto getSavings(Long clientId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = CACHE_KEY_PREFIX + clientId + "::" + startDate + "::" + endDate;
        Optional<ClientSavingsDashboardDto> cached = redisCacheService.get(
                cacheKey, new TypeReference<ClientSavingsDashboardDto>() {});
        if (cached.isPresent()) return cached.get();
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
        double awsEbs    = sumEbsSavings(awsAccounts, startInstant, endInstant);

        // Legacy rows: reported apart, never added into a total (D-12, AC-LED-11).
        BigDecimal legacyAwsSpot = sumLegacySpotSavings(awsAccounts, startDate, endDate);
        BigDecimal legacyGcpSpot = sumLegacySpotSavings(gcpAccounts, startDate, endDate);

        double awsRightsizing = sumSnapshots(awsAccounts, AWS_RIGHTSIZING,
                new TypeReference<DashboardData>() {}, ClientSavingsService::awsRightsizingAmounts);
        double gcpRightsizing = sumSnapshots(gcpAccounts, GCP_RIGHTSIZING,
                new TypeReference<List<GcpOptimizationRecommendation>>() {}, ClientSavingsService::gcpAmounts);
        double azureRightsizing = sumSnapshots(azureAccounts, AZURE_RIGHTSIZING,
                new TypeReference<AzureRightsizingResponse>() {}, ClientSavingsService::azureRightsizingAmounts);

        double awsReservations = sumSnapshots(awsAccounts, AWS_RESERVATIONS,
                new TypeReference<ReservationDto>() {}, ClientSavingsService::awsReservationAmounts);
        double gcpReservations = sumSnapshots(gcpAccounts, GCP_RESERVATIONS,
                new TypeReference<List<GcpOptimizationRecommendation>>() {}, ClientSavingsService::gcpAmounts);
        double azureReservations = sumSnapshots(azureAccounts, AZURE_RESERVATIONS,
                new TypeReference<AzureReservationData>() {}, data -> DoubleStream.of(data.getTotalMonthlySavings()));

        double realizedTotal  = awsSpot + gcpSpot + awsEbs;
        double potentialTotal = awsRightsizing + gcpRightsizing + azureRightsizing
                              + awsReservations + gcpReservations + azureReservations;

        ClientSavingsDashboardDto result = ClientSavingsDashboardDto.builder()
            .realizedSpotAws(awsSpot)
            .realizedSpotGcp(gcpSpot)
            .realizedEbsAws(awsEbs)
            .legacySpotUnverifiedAws(legacyAwsSpot)
            .legacySpotUnverifiedGcp(legacyGcpSpot)
            .potentialRightsizingAws(awsRightsizing)
            .potentialRightsizingGcp(gcpRightsizing)
            .potentialRightsizingAzure(azureRightsizing)
            .potentialReservationsAws(awsReservations)
            .potentialReservationsGcp(gcpReservations)
            .potentialReservationsAzure(azureReservations)
            .realizedTotal(realizedTotal)
            .potentialTotal(potentialTotal)
            .grandTotal(realizedTotal + potentialTotal)
            .timeSeries(buildTimeSeries(awsAccounts, gcpAccounts, startDate, endDate, startInstant, endInstant))
            .startDate(startDate.toString())
            .endDate(endDate.toString())
            .clientName(clientName)
            .accountCount(accounts.size())
            .cacheRefreshedAt(findOldestCacheDate(accounts))
            .build();
        redisCacheService.put(cacheKey, result, CACHE_TTL_MINUTES);
        return result;
    }

    /** Realized spot savings: non-legacy rows only, so 0 until the savings ledger exists. */
    private double sumSpotSavings(List<CloudAccount> accounts, LocalDate start, LocalDate end) {
        return accounts.stream()
            .mapToDouble(a -> dailySpotSavingsRepository
                .findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(a.getId(), start, end)
                .stream().mapToDouble(r -> r.getAmountSaved() != null ? r.getAmountSaved() : 0.0).sum())
            .sum();
    }

    /** Legacy spot savings in the range, rounded to cents for display; zero when there are none. */
    private BigDecimal sumLegacySpotSavings(List<CloudAccount> accounts, LocalDate start, LocalDate end) {
        if (accounts.isEmpty()) return BigDecimal.ZERO;
        List<Long> accountIds = accounts.stream().map(CloudAccount::getId).collect(Collectors.toList());
        Double legacy = dailySpotSavingsRepository.sumLegacyAmount(accountIds, start, end);
        return legacy == null ? BigDecimal.ZERO : BigDecimal.valueOf(legacy).setScale(2, RoundingMode.HALF_UP);
    }

    private double sumEbsSavings(List<CloudAccount> accounts, Instant start, Instant end) {
        return accounts.stream()
            .mapToDouble(a -> ebsVolumeActionRepository
                .findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", start, end)
                .stream().mapToDouble(r -> r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0).sum())
            .sum();
    }

    /**
     * Adds one provider's potential savings across its accounts. Only positive amounts count: a
     * negative "saving" is a cost increase. An account with no snapshot counts 0 quietly, because
     * its provider page was never opened. A snapshot that can't be read, or lacks the part
     * {@code amounts} reads, also counts 0 but is logged: it means this tab and the snapshot's
     * writer disagree, which is how these columns read 0 for every client unnoticed.
     */
    private <T> double sumSnapshots(List<CloudAccount> accounts, String keyPrefix, TypeReference<T> type,
                                    Function<T, DoubleStream> amounts) {
        double total = 0.0;
        for (CloudAccount account : accounts) {
            if (account.getProviderAccountId() == null) continue;
            String key = keyPrefix + account.getProviderAccountId();
            Optional<CachedData> row = cachedDataRepository.findById(key);
            if (row.isEmpty() || row.get().getJsonData() == null) continue;
            try {
                T snapshot = objectMapper.readValue(row.get().getJsonData(), type);
                if (snapshot == null) throw new IllegalStateException("snapshot is empty");
                total += amounts.apply(snapshot).filter(v -> v > 0).sum();
            } catch (Exception e) {
                log.warn("Savings snapshot {} not usable, counted as 0: {}", key, e.getMessage());
            }
        }
        return total;
    }

    /**
     * The EC2, EBS and Lambda lists the AWS dashboard adds up as rightsizing. Not the dashboard's
     * optimizationSummary total: that also adds idle-resource waste and the AutoSpotting, EBS disk
     * and CloudSitter estimates.
     */
    private static DoubleStream awsRightsizingAmounts(DashboardData snapshot) {
        DashboardData.Account account = snapshot.getSelectedAccount();
        if (account == null) throw new IllegalStateException("no selectedAccount");
        return Stream.of(account.getEc2Recommendations(), account.getEbsRecommendations(),
                        account.getLambdaRecommendations())
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings);
    }

    /** Reserved-instance purchases only: Savings Plan recommendations cover the same usage. */
    private static DoubleStream awsReservationAmounts(ReservationDto snapshot) {
        if (snapshot.getPurchaseRecommendations() == null) throw new IllegalStateException("no purchaseRecommendations");
        return snapshot.getPurchaseRecommendations().stream().mapToDouble(r -> parseAmount(r.getMonthlySavings()));
    }

    /** Recommender results, for rightsizing and CUD purchases alike. */
    private static DoubleStream gcpAmounts(List<GcpOptimizationRecommendation> recommendations) {
        return recommendations.stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings);
    }

    /**
     * Advisor resize and shutdown advice. Unpriced rows are placeholder zeros. Reservation and
     * savings-plan purchases are left to the Reservations column, which gets the same opportunities
     * from Azure's reservation recommendations.
     */
    private static DoubleStream azureRightsizingAmounts(AzureRightsizingResponse snapshot) {
        if (snapshot.getRecommendations() == null) throw new IllegalStateException("no recommendations");
        return snapshot.getRecommendations().stream()
            .filter(r -> !r.isSavingsUnknown())
            .filter(r -> !AzureRightsizingService.isCommitmentPurchase(r.getTargetSku()))
            .mapToDouble(AzureRightsizingRecommendation::getMonthlySavings);
    }

    /** ReservationService stores Cost Explorer's amount as text. */
    private static double parseAmount(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Unreadable AWS reservation saving '{}', counted as 0", value);
            return 0.0;
        }
    }

    private List<ClientSavingsDashboardDto.DailyPoint> buildTimeSeries(
            List<CloudAccount> aws, List<CloudAccount> gcp,
            LocalDate start, LocalDate end, Instant startInstant, Instant endInstant) {

        Map<LocalDate, double[]> byDate = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            byDate.put(d, new double[]{0, 0, 0});
        }

        // Spot points come from non-legacy rows only; legacy amounts never enter the series.
        for (CloudAccount a : aws) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[0] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : gcp) {
            dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(a.getId(), start, end)
                .forEach(r -> byDate.get(r.getDate())[1] += r.getAmountSaved() != null ? r.getAmountSaved() : 0.0);
        }
        for (CloudAccount a : aws) {
            ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", startInstant, endInstant)
                .forEach(r -> {
                    LocalDate d = r.getInitiatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    double[] arr = byDate.getOrDefault(d, new double[]{0, 0, 0});
                    arr[2] += r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0;
                });
        }

        return byDate.entrySet().stream()
            .map(e -> new ClientSavingsDashboardDto.DailyPoint(
                e.getKey().toString(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
            .collect(Collectors.toList());
    }

    /** Date of the oldest snapshot the potential columns were computed from, or null when none exists. */
    private String findOldestCacheDate(List<CloudAccount> accounts) {
        return accounts.stream()
            .filter(a -> a.getProviderAccountId() != null)
            .flatMap(a -> snapshotPrefixes(a.getProvider()).stream().map(prefix -> prefix + a.getProviderAccountId()))
            .map(cachedDataRepository::findById)
            .flatMap(Optional::stream)
            .map(CachedData::getLastUpdated)
            .filter(Objects::nonNull)
            .map(LocalDateTime::toLocalDate)
            .min(LocalDate::compareTo)
            .map(LocalDate::toString)
            .orElse(null);
    }

    /** The snapshots getSavings reads for an account of this provider. */
    private static List<String> snapshotPrefixes(String provider) {
        if ("AWS".equals(provider)) return List.of(AWS_RIGHTSIZING, AWS_RESERVATIONS);
        if ("GCP".equals(provider)) return List.of(GCP_RIGHTSIZING, GCP_RESERVATIONS);
        if ("Azure".equals(provider)) return List.of(AZURE_RIGHTSIZING, AZURE_RESERVATIONS);
        return List.of();
    }
}
