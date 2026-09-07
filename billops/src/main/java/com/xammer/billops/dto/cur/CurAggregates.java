package com.xammer.billops.dto.cur;

import java.util.ArrayList;
import java.util.List;

/**
 * The aggregated result of parsing one billing period's CUR 2.0 export.
 *
 * Raw CUR is line-item level — a single active account can emit hundreds of
 * thousands of rows per month — so nothing here holds raw rows. Ingestion
 * streams the export files and folds them into these bounded summaries, which
 * are what actually get persisted into the shared {@code cached_data} table.
 * That keeps CUR storage in the same order of magnitude as the Cost Explorer
 * snapshots BillOps already reads, rather than requiring a new line-item table.
 *
 * Shapes deliberately mirror the Cost Explorer snapshot entries written by
 * xamops-service's CostDataSnapshotService, so the BillOps read path can treat
 * CUR and CE as interchangeable sources and fall back cleanly.
 */
public class CurAggregates {

    /** Sum of unblended cost across every line item, including tax, fees, credits and refunds. */
    private double total;

    /** Billing period this was aggregated from, as "YYYY-MM". */
    private String billingPeriod;

    /** Identifier of the manifest this was built from — used to detect a re-delivered export. */
    private String assemblyId;

    /** Number of CUR rows folded in. Diagnostic only; a sudden drop signals a partial export. */
    private long rowCount;

    private List<DailyEntry> daily = new ArrayList<>();
    private List<SvcRegionEntry> svcRegion = new ArrayList<>();
    private List<SvcUsageEntry> svcUsage = new ArrayList<>();

    public double getTotal() { return total; }
    public void setTotal(double total) { this.total = total; }

    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }

    public String getAssemblyId() { return assemblyId; }
    public void setAssemblyId(String assemblyId) { this.assemblyId = assemblyId; }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public List<DailyEntry> getDaily() { return daily; }
    public void setDaily(List<DailyEntry> daily) { this.daily = daily; }

    public List<SvcRegionEntry> getSvcRegion() { return svcRegion; }
    public void setSvcRegion(List<SvcRegionEntry> svcRegion) { this.svcRegion = svcRegion; }

    public List<SvcUsageEntry> getSvcUsage() { return svcUsage; }
    public void setSvcUsage(List<SvcUsageEntry> svcUsage) { this.svcUsage = svcUsage; }

    /**
     * One day of the billing period. Present because DAILY granularity is what
     * makes CUR a strict upgrade over the CE daily snapshots rather than a
     * downgrade — a MONTHLY export could not reproduce these.
     */
    public static class DailyEntry {
        private String date;
        private double amount;

        public DailyEntry() {}

        public DailyEntry(String date, double amount) {
            this.date = date;
            this.amount = amount;
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
    }

    /** Matches the {service, region, cost} shape of CE's AWS_MONTHLY_SVC_REGION snapshot. */
    public static class SvcRegionEntry {
        private String service;
        private String region;
        private double cost;

        public SvcRegionEntry() {}

        public SvcRegionEntry(String service, String region, double cost) {
            this.service = service;
            this.region = region;
            this.cost = cost;
        }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }

    /**
     * Service x region x usage type, with usage quantity. Region is carried here
     * (unlike CE's equivalent snapshot, which drops it) so the detailed breakdown
     * can be filtered by region without a second lookup — the gap that forced
     * CostService.getCostByResource to ignore its region argument when reading
     * from a CE snapshot.
     */
    public static class SvcUsageEntry {
        private String service;
        private String region;
        private String usageType;
        private double cost;
        private double quantity;
        private String unit;

        public SvcUsageEntry() {}

        public SvcUsageEntry(String service, String region, String usageType,
                             double cost, double quantity, String unit) {
            this.service = service;
            this.region = region;
            this.usageType = usageType;
            this.cost = cost;
            this.quantity = quantity;
            this.unit = unit;
        }

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
}
