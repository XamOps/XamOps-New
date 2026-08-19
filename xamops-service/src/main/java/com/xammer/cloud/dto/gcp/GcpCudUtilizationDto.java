package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Utilization and realized savings for a single GCP committed-use discount.
 *
 * <p>{@link #utilizationPercentage} and {@link #realizedSavings} are nullable on purpose.
 * GCP only exposes per-hour commitment consumption through the BigQuery billing export,
 * so when that is not configured these fields are {@code null} and
 * {@link #dataUnavailableReason} explains why. Consumers must render "not available"
 * rather than substituting zero or a placeholder, and must exclude nulls from any
 * aggregate rather than counting them as 0% utilized.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpCudUtilizationDto {

    private String cudName;

    /** Percentage of the commitment actually consumed, or null when not measurable. */
    private Double utilizationPercentage;

    /** Realized savings in USD, or null when not measurable. */
    private Double realizedSavings;

    /** e.g., "LAST_30_DAYS" */
    private String analysisPeriod;

    /**
     * Set when {@link #utilizationPercentage} or {@link #realizedSavings} could not be
     * determined, so the UI can distinguish "no data" from "zero".
     */
    private String dataUnavailableReason;
}
