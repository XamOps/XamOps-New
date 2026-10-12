package com.xammer.cloud.service.automation.workload;

import java.time.Instant;

/**
 * Where a decision's numbers came from.
 *
 * <p>Mandatory on every decision. The harness's central complaint about the Go output was that the
 * numbers arrived with no stated basis: a percentile nobody could name, over a window nobody
 * recorded, from a source nobody identified. A recommendation that cannot state its basis is not
 * reviewable, and an unreviewable recommendation should not be applied.
 *
 * @param percentile     the percentile actually used — never a substituted neighbour
 * @param windowHours    the policy window
 * @param windowStart    the observed window's start, from the usage source
 * @param windowEnd      the observed window's end
 * @param sampleCoverage observed / expected samples, the lowest across containers
 * @param usageSource    the provider's name; {@code "none"} while only the stub is wired
 * @param evaluatedAt    when this decision was computed
 * @param policySource   whether a human chose the policy values, or they are defaults
 */
public record Provenance(int percentile,
                         int windowHours,
                         Instant windowStart,
                         Instant windowEnd,
                         double sampleCoverage,
                         String usageSource,
                         Instant evaluatedAt,
                         WorkloadPolicy.Source policySource) {

    public Provenance {
        if (usageSource == null || usageSource.isBlank()) {
            throw new IllegalArgumentException("usageSource is mandatory: a number must say where it came from");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt is mandatory");
        }
        if (policySource == null) {
            throw new IllegalArgumentException("policySource is mandatory");
        }
    }
}
