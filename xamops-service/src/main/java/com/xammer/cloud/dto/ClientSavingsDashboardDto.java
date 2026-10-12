package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ClientSavingsDashboardDto {

    // Realized savings (from actual DB rows in the requested date range). Spot reads only
    // non-legacy rows, so it is 0 until the savings ledger exists (D-12).
    private double realizedSpotAws;
    private double realizedSpotGcp;
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

    // Daily time series (realized only — non-legacy spot rows + EBS actions)
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
        private double awsSpot;
        private double gcpSpot;
        private double awsEbs;
    }
}
