package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single reservation in the inventory.
 *
 * <p>{@link #utilizationPercentage} is only meaningful when {@link #utilizationMeasured} is
 * true. The Cost Explorer lookup that populates it returns {@code 0.0} on failure, and
 * downstream logic treated that as "0% utilized" — manufacturing a
 * "Low Utilization (0.0%)" modification recommendation out of an API error.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationInventoryDto {

    private String reservationId;
    private String offeringType;
    private String instanceType;
    private String scope;
    private String availabilityZone;
    private long duration;
    private Instant start;
    private Instant end;
    private int instanceCount;
    private String state;

    /** Percentage of reserved hours used; valid only when {@link #utilizationMeasured}. */
    private double utilizationPercentage;

    /**
     * True when {@link #utilizationPercentage} came from a successful Cost Explorer
     * response rather than a failed lookup defaulting to zero.
     */
    private boolean utilizationMeasured;

    /**
     * Convenience constructor for the pre-existing field set. Leaves
     * {@link #utilizationMeasured} false so callers must set it explicitly once a real
     * utilization figure has been obtained.
     */
    public ReservationInventoryDto(String reservationId, String offeringType, String instanceType,
                                    String scope, String availabilityZone, long duration,
                                    Instant start, Instant end, int instanceCount, String state,
                                    double utilizationPercentage) {
        this.reservationId = reservationId;
        this.offeringType = offeringType;
        this.instanceType = instanceType;
        this.scope = scope;
        this.availabilityZone = availabilityZone;
        this.duration = duration;
        this.start = start;
        this.end = end;
        this.instanceCount = instanceCount;
        this.state = state;
        this.utilizationPercentage = utilizationPercentage;
        this.utilizationMeasured = false;
    }
}
