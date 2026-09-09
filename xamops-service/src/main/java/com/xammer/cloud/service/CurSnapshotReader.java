package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves reconciled AWS Cost and Usage Report data to the XamOps FinOps pages,
 * mapped into the same DTO shapes the Cost Explorer snapshots already use.
 *
 * WHY
 * Cost Explorer is an aggregation over AWS's billing data and its totals routinely
 * fail to reconcile with the invoice: provisional figures for the most recent days,
 * amortisation choices, and credits/refunds/tax redistributed across dimensions.
 * CUR is the source AWS generates the invoice from. Once BillOps needed the
 * reconciled figure for invoicing, having the XamOps cost pages continue to show a
 * different number for the same closed month would be worse than either source
 * alone — a customer comparing the two would find them disagreeing with no
 * explanation.
 *
 * OWNERSHIP
 * This class only READS. Detection and ingestion live in BillOps
 * (CurIngestionService / CurSnapshotScheduler), which writes bounded monthly
 * aggregates into the shared cached_data table under AWS_CUR_* keys. That mirrors
 * the existing arrangement in the opposite direction — BillOps already reads the
 * AWS_MONTHLY_* keys this service writes — so no ingestion is duplicated. If
 * BillOps is not deployed or an account has no export, every method here returns
 * empty and the caller falls back to the Cost Explorer snapshot untouched.
 *
 * SCOPE
 * Closed months only. The caller is responsible for that check, and
 * CostDataSnapshotService's read helpers already apply it.
 */
@Service
public class CurSnapshotReader {

    private static final Logger logger = LoggerFactory.getLogger(CurSnapshotReader.class);

    // Written by BillOps' CurIngestionService. Keep in step with the constants there.
    private static final String K_TOTAL      = "AWS_CUR_TOTAL::";
    private static final String K_DAILY      = "AWS_CUR_DAILY::";
    private static final String K_SVC_REGION = "AWS_CUR_SVC_REGION::";
    private static final String K_SVC_USAGE  = "AWS_CUR_SVC_USAGE::";
    private static final String K_STATE      = "AWS_CUR_STATE::";

    /** Product name CUR uses for CloudFront, needed for the CF usage breakdown. */
    private static final String CLOUDFRONT_SERVICE = "Amazon CloudFront";

    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    /**
     * Off by default in XamOps.
     *
     * Deliberate asymmetry with BillOps, where CUR is on because an invoice must
     * match the bill. Here it changes numbers already on customer-facing cost pages,
     * so it is opt-in per environment: enable it after confirming CUR and Cost
     * Explorer reconcile for a real account (compare via BillOps'
     * /api/billops/cur/reconcile). With it off, nothing about the FinOps pages
     * changes.
     */
    @Value("${xamops.cur.enabled:false}")
    private boolean curEnabled;

    public CurSnapshotReader(CachedDataRepository cachedDataRepository, ObjectMapper objectMapper) {
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * True when a complete CUR aggregate exists for this account and month.
     * Checks the sentinel BillOps writes last, so a half-written month reads as absent.
     */
    public boolean hasCurData(String accountId, YearMonth ym) {
        if (!curEnabled || accountId == null || ym == null) {
            return false;
        }
        try {
            return cachedDataRepository.existsById(K_STATE + accountId + "::" + ym);
        } catch (Exception e) {
            logger.debug("[CUR] State check failed for {} {}: {}", accountId, ym, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Reads mapped onto the existing snapshot DTO shapes
    // ------------------------------------------------------------------------

    /** Reconciled monthly total, including tax, fees, credits and refunds. */
    public Optional<Double> readMonthlyTotal(String accountId, YearMonth ym) {
        if (!hasCurData(accountId, ym)) return Optional.empty();
        return read(K_TOTAL + accountId + "::" + ym, new TypeReference<Double>() {});
    }

    /** Service breakdown, aggregated from CUR's service x region rows. */
    public Optional<List<CostDto>> readBreakdownSvc(String accountId, YearMonth ym) {
        return readSvcRegionRaw(accountId, ym).map(rows -> {
            Map<String, Double> byService = new LinkedHashMap<>();
            for (CurSvcRegion r : rows) {
                byService.merge(safe(r.getService()), r.getCost(), Double::sum);
            }
            return toCostDtos(byService);
        });
    }

    /** Region breakdown, aggregated from the same rows. */
    public Optional<List<CostDto>> readBreakdownRgn(String accountId, YearMonth ym) {
        return readSvcRegionRaw(accountId, ym).map(rows -> {
            Map<String, Double> byRegion = new LinkedHashMap<>();
            for (CurSvcRegion r : rows) {
                byRegion.merge(safe(r.getRegion()), r.getCost(), Double::sum);
            }
            return toCostDtos(byRegion);
        });
    }

    /** Service x region breakdown — a direct shape match. */
    public Optional<List<DetailedCostDto>> readSvcRegion(String accountId, YearMonth ym) {
        return readSvcRegionRaw(accountId, ym).map(rows -> rows.stream()
                .filter(r -> r.getCost() != 0.0)
                .map(r -> new DetailedCostDto(safe(r.getService()), safe(r.getRegion()), r.getCost()))
                .sorted(Comparator.comparingDouble(DetailedCostDto::getCost).reversed())
                .collect(java.util.stream.Collectors.toList()));
    }

    /**
     * Daily cost for the month.
     *
     * Returns the snapshot service's own entry type so callers need no new shape.
     * Available at all because the CUR export is configured at DAILY granularity —
     * a MONTHLY export could not reproduce this, which is why it is worth the extra
     * row volume.
     */
    public Optional<List<CostDataSnapshotService.DailyCostEntry>> readDailyCost(String accountId, YearMonth ym) {
        if (!hasCurData(accountId, ym)) return Optional.empty();
        return read(K_DAILY + accountId + "::" + ym, new TypeReference<List<CurDaily>>() {})
                .map(rows -> {
                    List<CostDataSnapshotService.DailyCostEntry> out = new ArrayList<>();
                    for (CurDaily d : rows) {
                        out.add(new CostDataSnapshotService.DailyCostEntry(d.getDate(), d.getAmount()));
                    }
                    return out;
                });
    }

    /**
     * Service x usage type breakdown.
     *
     * CUR keeps region on each usage row; the Cost Explorer equivalent does not, so
     * rows are collapsed across regions here to match the existing shape. The
     * region-aware rows remain available via {@link #readSvcUsageWithRegion}.
     */
    public Optional<List<CostDataSnapshotService.SvcUsageEntry>> readSvcUsage(String accountId, YearMonth ym) {
        return readSvcUsageRaw(accountId, ym).map(rows -> {
            Map<String, double[]> merged = new LinkedHashMap<>();   // svc|usageType → [cost, qty]
            Map<String, String> units = new LinkedHashMap<>();
            for (CurSvcUsage u : rows) {
                String key = safe(u.getService()) + '\u0000' + safe(u.getUsageType());
                merged.compute(key, (k, v) -> v == null
                        ? new double[]{u.getCost(), u.getQuantity()}
                        : new double[]{v[0] + u.getCost(), v[1] + u.getQuantity()});
                if (u.getUnit() != null && !u.getUnit().isBlank()) {
                    units.putIfAbsent(key, u.getUnit());
                }
            }
            List<CostDataSnapshotService.SvcUsageEntry> out = new ArrayList<>();
            merged.forEach((k, v) -> {
                String[] parts = k.split("\u0000", -1);
                out.add(new CostDataSnapshotService.SvcUsageEntry(
                        parts[0], parts[1], round(v[0]), round(v[1]), units.getOrDefault(k, "")));
            });
            out.sort(Comparator.comparingDouble(CostDataSnapshotService.SvcUsageEntry::getCost).reversed());
            return out;
        });
    }

    /**
     * CloudFront region x usage type breakdown.
     *
     * Worth noting this is the case that previously needed a manual CSV upload
     * (BillOps' CloudFrontUsageService) to reconcile, because Cost Explorer's
     * grouping for CDN usage types did not match the invoice. Coming from CUR it is
     * just another slice of the same aggregate.
     */
    public Optional<List<CostDataSnapshotService.CfUsageEntry>> readCfUsage(String accountId, YearMonth ym) {
        return readSvcUsageRaw(accountId, ym).map(rows -> {
            List<CostDataSnapshotService.CfUsageEntry> out = new ArrayList<>();
            for (CurSvcUsage u : rows) {
                if (!CLOUDFRONT_SERVICE.equalsIgnoreCase(safe(u.getService()))) continue;
                out.add(new CostDataSnapshotService.CfUsageEntry(
                        safe(u.getRegion()), safe(u.getUsageType()),
                        u.getQuantity(), safe(u.getUnit()), u.getCost()));
            }
            out.sort(Comparator.comparingDouble(CostDataSnapshotService.CfUsageEntry::getCost).reversed());
            return out;
        });
    }

    /**
     * Usage rows with region retained.
     *
     * Not part of the Cost Explorer snapshot surface — exposed for resource and
     * region-scoped cost attribution, which CE can only answer by enabling
     * "Hourly and Resource Level Data" and paying per call.
     */
    public Optional<List<CurSvcUsage>> readSvcUsageWithRegion(String accountId, YearMonth ym) {
        return readSvcUsageRaw(accountId, ym);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private Optional<List<CurSvcRegion>> readSvcRegionRaw(String accountId, YearMonth ym) {
        if (!hasCurData(accountId, ym)) return Optional.empty();
        return read(K_SVC_REGION + accountId + "::" + ym, new TypeReference<List<CurSvcRegion>>() {})
                .filter(rows -> !rows.isEmpty());
    }

    private Optional<List<CurSvcUsage>> readSvcUsageRaw(String accountId, YearMonth ym) {
        if (!hasCurData(accountId, ym)) return Optional.empty();
        return read(K_SVC_USAGE + accountId + "::" + ym, new TypeReference<List<CurSvcUsage>>() {})
                .filter(rows -> !rows.isEmpty());
    }

    private <T> Optional<T> read(String key, TypeReference<T> typeRef) {
        try {
            return cachedDataRepository.findById(key).map(row -> {
                try {
                    return objectMapper.<T>readValue(row.getJsonData(), typeRef);
                } catch (Exception e) {
                    logger.warn("[CUR] Deserialize failed for {}: {}", key, e.getMessage());
                    return null;
                }
            });
        } catch (Exception e) {
            logger.warn("[CUR] DB read failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Zero-cost entries are dropped, but negative ones are kept: credits and refunds
     * are real invoice lines and removing them would inflate the displayed total
     * above what the customer was actually billed.
     */
    private List<CostDto> toCostDtos(Map<String, Double> byName) {
        List<CostDto> out = new ArrayList<>();
        byName.forEach((name, amount) -> {
            if (amount != 0.0) {
                out.add(new CostDto(name, round(amount)));
            }
        });
        out.sort(Comparator.comparingDouble(CostDto::getAmount).reversed());
        return out;
    }

    /**
     * Rounds an aggregated figure to six decimal places.
     *
     * Necessary because these values are re-summed on read: adding two already-stored
     * costs (100.50 + 46.08) yields 146.57999999999998 in binary floating point, and
     * a figure presented as reconciled against an AWS invoice should not carry that
     * noise into an API response. Six places matches the rounding BillOps applies
     * when it writes the aggregate, so both sides of the contract agree, and it stays
     * well below a cent so no real cost is lost.
     */
    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------------
    // Wire shapes written by BillOps' CurIngestionService
    // ------------------------------------------------------------------------

    /** Mirrors CurAggregates.SvcRegionEntry in the BillOps module. */
    public static class CurSvcRegion {
        private String service;
        private String region;
        private double cost;

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }

    /** Mirrors CurAggregates.SvcUsageEntry in the BillOps module. */
    public static class CurSvcUsage {
        private String service;
        private String region;
        private String usageType;
        private double cost;
        private double quantity;
        private String unit;

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getUsageType() { return usageType; }
        public void setUsageType(String usageType) { this.usageType = usageType; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
        public double getQuantity() { return quantity; }
        public void setQuantity(double quantity) { this.quantity = quantity; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
    }

    /** Mirrors CurAggregates.DailyEntry in the BillOps module. */
    public static class CurDaily {
        private String date;
        private double amount;

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
    }
}
