package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;
import java.util.List;

/**
 * The event pipeline's coverage of one AWS account, per region (design-aws.md §9.4, FR-EVT-02). A region is
 * {@code COVERED}, {@code MISSING}, {@code SKIPPED} or {@code UNKNOWN}; only COVERED lets a group in the
 * region convert. {@code applicable} is false for a read-only connection, which has no events stack.
 */
public record RegionCoverageDto(boolean applicable, List<Region> regions) {

    /** One region's verdict and when it was last checked. */
    public record Region(String region, String status, Instant checkedAt, Instant lastEventAt) {
    }
}
