package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single RI modification (exchange) recommendation.
 *
 * <p>{@link #estimatedMonthlySavings} is only meaningful when {@link #savingsUnknown} is
 * false. It previously carried a hardcoded {@code 50.0} for every recommendation, which was
 * rendered to users as a real dollar figure. The saving from an RI exchange depends on the
 * replacement offering's rate, so it is reported as unknown unless it has been computed.
 *
 * <p>{@link #recommendedInstanceCount} matters because AWS requires an exchange to preserve
 * reserved normalized units: stepping down one instance size halves the units per instance,
 * so the count must double for AWS to accept the modification.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationModificationRecommendationDto {

    private String reservationId;
    private String currentInstanceType;
    private String recommendedInstanceType;
    private String reason;

    /** Estimated monthly savings in USD; only valid when {@link #savingsUnknown} is false. */
    private double estimatedMonthlySavings;

    /** True when the saving could not be quantified. */
    private boolean savingsUnknown;

    /** Instance count required at the recommended size to preserve normalized units. */
    private Integer recommendedInstanceCount;

    /**
     * Convenience constructor for the four descriptive fields plus a known saving.
     */
    public ReservationModificationRecommendationDto(String reservationId, String currentInstanceType,
                                                     String recommendedInstanceType, String reason,
                                                     double estimatedMonthlySavings) {
        this.reservationId = reservationId;
        this.currentInstanceType = currentInstanceType;
        this.recommendedInstanceType = recommendedInstanceType;
        this.reason = reason;
        this.estimatedMonthlySavings = estimatedMonthlySavings;
        this.savingsUnknown = false;
    }
}
