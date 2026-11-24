package com.xammer.cloud.dto.autospotting.v2;

import java.time.Instant;

/**
 * The dashboard potential (plan-phase-14.md task 14.3, AC-CMP-04): the estimated monthly savings in USD
 * of the groups that count, plus how many groups were left out. {@code potentialMonthlySavings} sums
 * priced groups only and is null when none is priced (never 0); an unpriced group is counted in
 * {@code unpricedGroups}, an ineligible one in {@code ineligibleGroups}, and neither is added.
 */
public record PotentialDto(Double potentialMonthlySavings, int pricedGroups, int unpricedGroups,
                           int ineligibleGroups, boolean partial, Instant asOf) {
}
