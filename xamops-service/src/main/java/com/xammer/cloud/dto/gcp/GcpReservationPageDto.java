package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpReservationPageDto {

    /** Purchase recommendations from the Recommender API. */
    private List<GcpOptimizationRecommendation> cudRecommendations;

    /** Active / expired CUD inventory from RegionCommitmentsClient. */
    private List<GcpCommittedUseDiscountDto> inventory;

    /** Aggregated summary stats. */
    private Map<String, Object> summary;

    /** Month-by-month historical snapshot (up to 6 entries, newest last). */
    private List<Map<String, Object>> historicalData;

    /** Derived suggestions to renew/expand commitments. */
    private List<Map<String, Object>> modificationRecommendations;
}
