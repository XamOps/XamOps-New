package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EbsVolumeAction;
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
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.AccountMonth;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.ClientSpotSavings;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.DailyAccrual;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.ProviderSavings;
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
import java.time.YearMonth;
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
    private static final Currency USD = Currency.getInstance("USD");

    // Snapshots behind the potential-savings columns, keyed <prefix><providerAccountId>. Each is
    // read with the DTO its writer saves, so a renamed field breaks the build instead of reading 0.
    private static final String DASHBOARD          = "DASHBOARD_OPT::";     // DashboardData, all providers
    private static final String AWS_RESERVATIONS   = "RESERVATION_PAGE::";  // ReservationDto
    private static final String GCP_RIGHTSIZING    = "GCP_RIGHTSIZING::";   // List<GcpOptimizationRecommendation>
    private static final String GCP_RESERVATIONS   = "GCP_CUD_RECS::";      // same, written by the GCP reservations page
    private static final String AZURE_RIGHTSIZING  = "AZURE_RIGHTSIZING::"; // AzureRightsizingResponse
    private static final String AZURE_RESERVATIONS = "RESERVATION_PAGE::";  // AzureReservationData

    @Autowired RedisCacheService redisCacheService;
    @Autowired CloudAccountRepository cloudAccountRepository;
    @Autowired DailySpotSavingsRepository dailySpotSavingsRepository;
    @Autowired SavingsLedgerReadService ledgerReadService;
    @Autowired EbsVolumeActionRepository ebsVolumeActionRepository;
    @Autowired CachedDataRepository cachedDataRepository;
    @Autowired ObjectMapper objectMapper;

    /** Savings for every account of the client. */
    public ClientSavingsDashboardDto getSavings(Long clientId, LocalDate startDate, LocalDate endDate) {
        return getSavings(clientId, null, startDate, endDate);
    }

    /**
     * Savings for the client, or for one of its accounts when {@code accountId} is set.
     *
     * @throws NoSuchElementException when {@code accountId} is not one of this client's accounts
     */
    @Transactional(readOnly = true)
    public ClientSavingsDashboardDto getSavings(Long clientId, Long accountId, LocalDate startDate, LocalDate endDate) {
        // The key carries the client's latest statement close, so a period close is visible at once (design-ledger.md §10.3).
        String closedAt = ledgerReadService.latestClosedAt(clientId).map(Instant::toString).orElse("none");
        String cacheKey = CACHE_KEY_PREFIX + clientId + "::" + (accountId == null ? "all" : accountId)
                + "::" + startDate + "::" + endDate + "::" + closedAt;
        Optional<ClientSavingsDashboardDto> cached = redisCacheService.get(
                cacheKey, new TypeReference<ClientSavingsDashboardDto>() {});
        if (cached.isPresent()) return cached.get();

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId);
        String clientName = clientAccounts.isEmpty() ? "" : clientAccounts.get(0).getClient().getName();

        List<CloudAccount> accounts = clientAccounts;
        if (accountId != null) {
            accounts = clientAccounts.stream().filter(a -> accountId.equals(a.getId())).toList();
            if (accounts.isEmpty()) {
                throw new NoSuchElementException("Account " + accountId + " does not belong to client " + clientId);
            }
        }

        Instant startInstant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endInstant   = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // Every Spot figure reads the savings ledger through its one read service (FR-LED-27, AC-LED-11).
        // The client read is per client; for one account its figures are taken from the per-account months
        // and the account's own daily accrual.
        ClientSpotSavings ledger = ledgerReadService.getClientSpotSavings(clientId,
                YearMonth.from(startDate), YearMonth.from(endDate));
        ProviderSavings awsLedger = providerFigures(ledger, accountId, "AWS");
        ProviderSavings gcpLedger = providerFigures(ledger, accountId, "GCP");
        BigDecimal awsSpot = display(awsLedger.realized());
        BigDecimal gcpSpot = display(gcpLedger.realized());

        // EBS rows are read once per account and reused for the totals, the series and the per-account rows.
        Map<Long, List<EbsVolumeAction>> ebsRows = new HashMap<>();
        for (CloudAccount a : accounts) {
            if (isAws(a)) {
                ebsRows.put(a.getId(), ebsVolumeActionRepository
                        .findByAccountIdAndStatusAndInitiatedAtBetween(a.getId(), "SUCCESS", startInstant, endInstant));
            }
        }

        List<CloudAccount> awsAccounts = accounts.stream().filter(ClientSavingsService::isAws).toList();
        List<CloudAccount> gcpAccounts = accounts.stream().filter(ClientSavingsService::isGcp).toList();

        // Legacy rows: reported apart, never added into a total (D-12, AC-LED-11).
        BigDecimal legacyAwsSpot = sumLegacySpotSavings(awsAccounts, startDate, endDate);
        BigDecimal legacyGcpSpot = sumLegacySpotSavings(gcpAccounts, startDate, endDate);

        // Each snapshot feeds an account's figures, its row's date and the overall date: read once.
        Map<String, Optional<CachedData>> memo = new HashMap<>();
        Function<String, Optional<CachedData>> snapshots = key -> memo.computeIfAbsent(key, cachedDataRepository::findById);

        double awsEbs = 0;
        double rsAws = 0, rsGcp = 0, rsAzure = 0;
        double resAws = 0, resGcp = 0, resAzure = 0;
        double otherAws = 0, otherGcp = 0, otherAzure = 0;
        List<ClientSavingsDashboardDto.AccountSavings> rows = new ArrayList<>();

        for (CloudAccount a : accounts) {
            double accountSpot = isAws(a) || isGcp(a) ? display(accountRealized(ledger, a.getId())).doubleValue() : 0.0;
            double ebs = sumEbs(ebsRows.get(a.getId()));
            Potential p = potentialFor(a, snapshots);

            if (isAws(a)) {
                awsEbs += ebs;
                rsAws += p.rightsizing(); resAws += p.reservations(); otherAws += p.other();
            } else if (isGcp(a)) {
                rsGcp += p.rightsizing(); resGcp += p.reservations(); otherGcp += p.other();
            } else if (isAzure(a)) {
                rsAzure += p.rightsizing(); resAzure += p.reservations(); otherAzure += p.other();
            }

            rows.add(ClientSavingsDashboardDto.AccountSavings.builder()
                    .accountId(a.getId())
                    .accountName(a.getAccountName())
                    .provider(a.getProvider())
                    .providerAccountId(a.getProviderAccountId())
                    .rightsizing(p.rightsizing())
                    .reservations(p.reservations())
                    .other(p.other())
                    .potentialTotal(p.rightsizing() + p.reservations() + p.other())
                    .realizedTotal(accountSpot + ebs)
                    .snapshotDate(oldestSnapshotDate(List.of(a), snapshots))
                    .build());
        }

        double realizedTotal  = awsSpot.add(gcpSpot).doubleValue() + awsEbs;
        double potentialTotal = rsAws + rsGcp + rsAzure + resAws + resGcp + resAzure + otherAws + otherGcp + otherAzure;

        ClientSavingsDashboardDto result = ClientSavingsDashboardDto.builder()
            .realizedSpotAws(awsSpot)
            .realizedSpotGcp(gcpSpot)
            .realizedSpotMonths(realizedMonths(ledger, accountId).stream().map(YearMonth::toString).collect(Collectors.toList()))
            .shadowNotInvoicedAws(display(awsLedger.shadowNotInvoiced()))
            .shadowNotInvoicedGcp(display(gcpLedger.shadowNotInvoiced()))
            .accruingSpotAws(display(awsLedger.accruing()))
            .accruingSpotGcp(display(gcpLedger.accruing()))
            .spotSeriesLabel(ClientSavingsDashboardDto.SPOT_SERIES_LABEL)
            .realizedEbsAws(awsEbs)
            .legacySpotUnverifiedAws(legacyAwsSpot)
            .legacySpotUnverifiedGcp(legacyGcpSpot)
            .potentialRightsizingAws(rsAws)
            .potentialRightsizingGcp(rsGcp)
            .potentialRightsizingAzure(rsAzure)
            .potentialReservationsAws(resAws)
            .potentialReservationsGcp(resGcp)
            .potentialReservationsAzure(resAzure)
            .potentialOtherAws(otherAws)
            .potentialOtherGcp(otherGcp)
            .potentialOtherAzure(otherAzure)
            .realizedTotal(realizedTotal)
            .potentialTotal(potentialTotal)
            .grandTotal(realizedTotal + potentialTotal)
            .timeSeries(buildTimeSeries(accounts, dailyAccrual(ledger, accountId, accounts, startDate, endDate),
                    ebsRows, startDate, endDate))
            .accounts(rows)
            .selectedAccountId(accountId)
            .startDate(startDate.toString())
            .endDate(endDate.toString())
            .clientName(clientName)
            .accountCount(accounts.size())
            .cacheRefreshedAt(oldestSnapshotDate(accounts, snapshots))
            .build();
        redisCacheService.put(cacheKey, result, CACHE_TTL_MINUTES);
        return result;
    }

    /** A ledger amount at the currency's minor unit, rounded once and half-up, for display. */
    private static BigDecimal display(Money money) {
        return money.toMinorUnit().amount();
    }

    private static Money usd(BigDecimal amount) {
        return new Money(amount, USD);
    }

    /** One provider's ledger figures: the client-wide ones, or only those of {@code accountId} when it is set. */
    private static ProviderSavings providerFigures(ClientSpotSavings ledger, Long accountId, String provider) {
        if (accountId == null) return ledger.provider(provider);
        BigDecimal realized = BigDecimal.ZERO, shadow = BigDecimal.ZERO, accruing = BigDecimal.ZERO;
        for (AccountMonth m : ledger.months()) {
            if (!accountId.equals(m.cloudAccountId()) || !provider.equals(m.provider())) continue;
            realized = realized.add(m.realized().amount());
            shadow = shadow.add(m.shadowNotInvoiced().amount());
            accruing = accruing.add(m.accruing().amount());
        }
        return new ProviderSavings(provider, usd(realized), usd(shadow), usd(accruing));
    }

    /** One account's realized Spot savings over the ledger months in range. */
    private static Money accountRealized(ClientSpotSavings ledger, Long accountId) {
        BigDecimal realized = ledger.months().stream()
                .filter(m -> accountId.equals(m.cloudAccountId()))
                .map(m -> m.realized().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return usd(realized);
    }

    /** The months behind the realized figure: client-wide, or the closed invoiceable ones of one account. */
    private static List<YearMonth> realizedMonths(ClientSpotSavings ledger, Long accountId) {
        if (accountId == null) return ledger.realizedMonths();
        return ledger.months().stream()
                .filter(m -> accountId.equals(m.cloudAccountId()) && m.invoiceable()
                        && ("CLOSED".equals(m.status()) || "INVOICED".equals(m.status())))
                .map(AccountMonth::month)
                .distinct()
                .toList();
    }

    /** Daily ledger accrual: the client's, or the selected account's own when {@code accountId} is set. */
    private List<DailyAccrual> dailyAccrual(ClientSpotSavings ledger, Long accountId, List<CloudAccount> accounts,
                                            LocalDate start, LocalDate end) {
        if (accountId == null) return ledger.daily();
        List<DailyAccrual> daily = new ArrayList<>();
        for (CloudAccount a : accounts) {
            if (isAws(a) || isGcp(a)) daily.addAll(ledgerReadService.getDailyAccrual(a, start, end));
        }
        return daily;
    }

    /** One account's monthly potential savings, split the way the tab's columns are. */
    private record Potential(double rightsizing, double reservations, double other) {
        static final Potential NONE = new Potential(0, 0, 0);
    }

    /**
     * Reads one account's snapshots. "Other" is everything the provider's dashboard counts as
     * potential savings that the Rightsizing and Reservations columns don't, so each account's
     * Rightsizing + Other matches its dashboard figure:
     * <ul>
     *   <li>AWS: the dashboard total is rightsizing + priced idle resources + the AutoSpotting,
     *       EBS disk and CloudSitter estimates. Other is that total minus the rightsizing counted
     *       here, read from the same snapshot.</li>
     *   <li>GCP: the dashboard total is idle resources + rightsizing (minus resources already
     *       counted as idle) + the spot MIG and disk estimates. Other is that total minus the
     *       rightsizing counted here, never below zero.</li>
     *   <li>Azure: the snapshot's total holds only the disk estimate, and idle resources are listed
     *       beside it. Other is the disk estimate plus the priced idle resources; rightsizing for a
     *       resource listed as idle is dropped, as the Azure FinOps report does, because deleting
     *       and resizing it are alternatives.</li>
     * </ul>
     * The AWS and GCP estimates include AutoSpotting potential. Once the spot ledger reports
     * realized savings, a converted group can appear as both realized and potential until the
     * next dashboard refresh drops it from the estimate.
     *
     * <p>Rightsizing snapshots are read before the dashboard snapshot on purpose: the tests stub
     * them by exact key, and Mockito's strict stubs reject a lookup of another key first.
     */
    private Potential potentialFor(CloudAccount account, Function<String, Optional<CachedData>> snapshots) {
        String id = account.getProviderAccountId();
        if (id == null || id.isBlank()) return Potential.NONE;

        if (isAws(account)) {
            Optional<DashboardData.Account> dash = read(snapshots, DASHBOARD + id, new TypeReference<DashboardData>() {})
                    .map(DashboardData::getSelectedAccount);
            double rightsizing = dash.map(a -> positive(awsRightsizingAmounts(a))).orElse(0.0);
            double reservations = read(snapshots, AWS_RESERVATIONS + id, new TypeReference<ReservationDto>() {})
                    .map(r -> positive(awsReservationAmounts(r))).orElse(0.0);
            double other = Math.max(0, dashboardTotal(dash) - rightsizing);
            return new Potential(rightsizing, reservations, other);
        }

        if (isGcp(account)) {
            double rightsizing = read(snapshots, GCP_RIGHTSIZING + id, new TypeReference<List<GcpOptimizationRecommendation>>() {})
                    .map(recs -> positive(gcpAmounts(recs))).orElse(0.0);
            Optional<DashboardData.Account> dash = read(snapshots, DASHBOARD + id, new TypeReference<DashboardData>() {})
                    .map(DashboardData::getSelectedAccount);
            double reservations = read(snapshots, GCP_RESERVATIONS + id, new TypeReference<List<GcpOptimizationRecommendation>>() {})
                    .map(recs -> positive(gcpAmounts(recs))).orElse(0.0);
            double other = Math.max(0, dashboardTotal(dash) - rightsizing);
            return new Potential(rightsizing, reservations, other);
        }

        if (isAzure(account)) {
            Optional<AzureRightsizingResponse> advice = read(snapshots, AZURE_RIGHTSIZING + id, new TypeReference<AzureRightsizingResponse>() {});
            Optional<DashboardData.Account> dash = read(snapshots, DASHBOARD + id, new TypeReference<DashboardData>() {})
                    .map(DashboardData::getSelectedAccount);
            List<DashboardData.WastedResource> waste = dash.map(DashboardData.Account::getWastedResources)
                    .orElse(List.of());
            Set<String> idleNames = waste.stream()
                    .map(DashboardData.WastedResource::getResourceName)
                    .filter(n -> n != null && !n.isBlank())
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            double rightsizing = advice.map(r -> positive(azureRightsizingAmounts(r, idleNames))).orElse(0.0);
            double reservations = read(snapshots, AZURE_RESERVATIONS + id, new TypeReference<AzureReservationData>() {})
                    .map(r -> Math.max(0, r.getTotalMonthlySavings())).orElse(0.0);
            double idle = positive(waste.stream()
                    .filter(DashboardData.WastedResource::isBillableWaste)
                    .mapToDouble(DashboardData.WastedResource::getMonthlySavings));
            double other = Math.max(0, dashboardTotal(dash)) + idle;
            return new Potential(rightsizing, reservations, other);
        }

        return Potential.NONE;
    }

    /**
     * A snapshot by key. Empty when there is none, quietly: the provider page was never opened.
     * Also empty when it can't be read, but logged: that means this tab and the snapshot's writer
     * disagree, which is how these columns read 0 for every client unnoticed.
     */
    private <T> Optional<T> read(Function<String, Optional<CachedData>> snapshots, String key, TypeReference<T> type) {
        Optional<CachedData> row = snapshots.apply(key);
        if (row.isEmpty() || row.get().getJsonData() == null) return Optional.empty();
        try {
            T snapshot = objectMapper.readValue(row.get().getJsonData(), type);
            if (snapshot == null) log.warn("Savings snapshot {} is empty, counted as 0", key);
            return Optional.ofNullable(snapshot);
        } catch (Exception e) {
            log.warn("Savings snapshot {} not usable, counted as 0: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Only positive amounts count: a negative "saving" is a cost increase. */
    private static double positive(DoubleStream amounts) {
        return amounts.filter(v -> v > 0).sum();
    }

    /** The dashboard's potential-savings figure for the account, or 0 without a snapshot. */
    private static double dashboardTotal(Optional<DashboardData.Account> account) {
        return account.map(DashboardData.Account::getOptimizationSummary)
                .map(DashboardData.OptimizationSummary::getTotalPotentialSavings)
                .orElse(0.0);
    }

    /** The EC2, EBS and Lambda lists the AWS dashboard adds up as rightsizing. */
    private static DoubleStream awsRightsizingAmounts(DashboardData.Account account) {
        return Stream.of(account.getEc2Recommendations(), account.getEbsRecommendations(),
                        account.getLambdaRecommendations())
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings);
    }

    /** Reserved-instance purchases only: Savings Plan recommendations cover the same usage. */
    private static DoubleStream awsReservationAmounts(ReservationDto snapshot) {
        if (snapshot.getPurchaseRecommendations() == null) return DoubleStream.empty();
        return snapshot.getPurchaseRecommendations().stream().mapToDouble(r -> parseAmount(r.getMonthlySavings()));
    }

    /** Recommender results, for rightsizing and CUD purchases alike. */
    private static DoubleStream gcpAmounts(List<GcpOptimizationRecommendation> recommendations) {
        return recommendations.stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings);
    }

    /**
     * Advisor resize and shutdown advice. Unpriced rows are placeholder zeros. Reservation and
     * savings-plan purchases are left to the Reservations column, which gets the same opportunities
     * from Azure's reservation recommendations. Resources listed as idle are counted under Other.
     */
    private static DoubleStream azureRightsizingAmounts(AzureRightsizingResponse snapshot, Set<String> idleNames) {
        if (snapshot.getRecommendations() == null) return DoubleStream.empty();
        return snapshot.getRecommendations().stream()
            .filter(r -> !r.isSavingsUnknown())
            .filter(r -> !AzureRightsizingService.isCommitmentPurchase(r.getTargetSku()))
            .filter(r -> r.getResourceName() == null || !idleNames.contains(r.getResourceName().toLowerCase()))
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

    private static double sumEbs(List<EbsVolumeAction> rows) {
        if (rows == null) return 0.0;
        return rows.stream().mapToDouble(r -> r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0).sum();
    }

    /** Legacy spot savings in the range, rounded to cents for display; zero when there are none. */
    private BigDecimal sumLegacySpotSavings(List<CloudAccount> accounts, LocalDate start, LocalDate end) {
        if (accounts.isEmpty()) return BigDecimal.ZERO;
        List<Long> accountIds = accounts.stream().map(CloudAccount::getId).collect(Collectors.toList());
        Double legacy = dailySpotSavingsRepository.sumLegacyAmount(accountIds, start, end);
        return legacy == null ? BigDecimal.ZERO : BigDecimal.valueOf(legacy).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Realized savings per day. Spot points are the daily accrual of the current ledger entries, before
     * statement rounding; legacy amounts never enter the series, and each day's figure is rounded once for display.
     */
    private List<ClientSavingsDashboardDto.DailyPoint> buildTimeSeries(
            List<CloudAccount> accounts, List<DailyAccrual> accrual,
            Map<Long, List<EbsVolumeAction>> ebsRows, LocalDate start, LocalDate end) {
        Map<LocalDate, BigDecimal[]> spotByDate = new TreeMap<>();
        Map<LocalDate, double[]> ebsByDate = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            spotByDate.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            ebsByDate.put(d, new double[]{0});
        }
        for (DailyAccrual a : accrual) {
            BigDecimal[] point = spotByDate.get(a.date());
            if (point == null) continue;
            int slot = "GCP".equals(a.provider()) ? 1 : "AWS".equals(a.provider()) ? 0 : -1;
            if (slot >= 0) point[slot] = point[slot].add(a.net().amount());
        }
        for (CloudAccount a : accounts) {
            for (EbsVolumeAction r : ebsRows.getOrDefault(a.getId(), List.of())) {
                double[] day = ebsByDate.get(r.getInitiatedAt().atZone(ZoneOffset.UTC).toLocalDate());
                if (day != null) day[0] += r.getEstimatedSavings() != null ? r.getEstimatedSavings() : 0.0;
            }
        }
        return spotByDate.entrySet().stream()
            .map(e -> new ClientSavingsDashboardDto.DailyPoint(
                e.getKey().toString(),
                display(usd(e.getValue()[0])),
                display(usd(e.getValue()[1])),
                ebsByDate.get(e.getKey())[0]))
            .collect(Collectors.toList());
    }

    /** Date of the oldest snapshot the potential columns were computed from, or null when none exists. */
    private String oldestSnapshotDate(List<CloudAccount> accounts, Function<String, Optional<CachedData>> snapshots) {
        return accounts.stream()
            .filter(a -> a.getProviderAccountId() != null && !a.getProviderAccountId().isBlank())
            .flatMap(a -> snapshotPrefixes(a.getProvider()).stream().map(prefix -> prefix + a.getProviderAccountId()))
            .map(snapshots)
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
        if ("AWS".equals(provider)) return List.of(DASHBOARD, AWS_RESERVATIONS);
        if ("GCP".equals(provider)) return List.of(GCP_RIGHTSIZING, DASHBOARD, GCP_RESERVATIONS);
        if ("Azure".equals(provider)) return List.of(AZURE_RIGHTSIZING, DASHBOARD, AZURE_RESERVATIONS);
        return List.of();
    }

    private static boolean isAws(CloudAccount a)   { return "AWS".equals(a.getProvider()); }
    private static boolean isGcp(CloudAccount a)   { return "GCP".equals(a.getProvider()); }
    private static boolean isAzure(CloudAccount a) { return "Azure".equals(a.getProvider()); }
}
