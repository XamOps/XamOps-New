package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ClientSavingsDashboardDto {

    // Spot savings come from the savings ledger through SavingsLedgerReadService only (FR-LED-27), at the
    // currency's minor unit. "Realized" is closed AND invoiceable statements: 0 until a month is invoiceable
    // (Phase 35). A closed statement that is not invoiceable is "Not invoiced"; an open month is "Accruing
    // (not final)". None of the three is ever added into another.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal realizedSpotAws;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal realizedSpotGcp;
    // The months behind the realized figure ("2026-08"), so the UI can say which closed statements it sums.
    private List<String> realizedSpotMonths;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal shadowNotInvoicedAws;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal shadowNotInvoicedGcp;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal accruingSpotAws;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal accruingSpotGcp;
    // How the Spot points of the time series are measured; shown beside the chart.
    private String spotSeriesLabel;
    // The currency of every Spot amount above (v1 is USD only).
    @lombok.Builder.Default
    private String spotCurrency = "USD";
    public static final String SPOT_SERIES_LABEL = "Daily accrual, before statement rounding";
    private double realizedEbsAws;

    // Legacy daily_spot_savings rows in the range (D-12, AC-LED-11). Never added into any total
    // below or into the time series; the UI shows them only as "Legacy (unverified)".
    private BigDecimal legacySpotUnverifiedAws;
    private BigDecimal legacySpotUnverifiedGcp;

    // Potential savings (from latest CachedData snapshots — point-in-time)
    private double potentialRightsizingAws;
    private double potentialRightsizingGcp;
    private double potentialRightsizingAzure;
    private double potentialReservationsAws;
    private double potentialReservationsGcp;
    private double potentialReservationsAzure;

    // Aggregated totals
    private double realizedTotal;
    private double potentialTotal;
    private double grandTotal;

    // Daily time series: ledger daily accrual (Spot) and EBS actions. Not billable, not realized.
    private List<DailyPoint> timeSeries;

    // Metadata
    private String startDate;
    private String endDate;
    private String clientName;
    private int accountCount;
    private String cacheRefreshedAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyPoint {
        private String date;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal awsSpot;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal gcpSpot;
        private double awsEbs;
    }
}
