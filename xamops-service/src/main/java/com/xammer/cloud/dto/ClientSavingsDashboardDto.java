package com.xammer.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClientSavingsDashboardDto {

    // Realized savings (from actual DB rows in the requested date range)
    private double realizedSpotAws;
    private double realizedSpotGcp;
    private double realizedSpotAzure;
    private double realizedEbsAws;

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

    // Daily time series (realized only — spot + EBS have daily DB rows)
    private List<DailyPoint> timeSeries;

    // Metadata
    private String startDate;
    private String endDate;
    private String clientName;
    private int accountCount;
    private String cacheRefreshedAt;

    @Data
    public static class DailyPoint {
        private final String date;
        private final double awsSpot;
        private final double gcpSpot;
        private final double azureSpot;
        private final double awsEbs;
    }
}
