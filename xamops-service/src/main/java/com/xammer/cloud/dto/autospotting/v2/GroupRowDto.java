package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.service.autospotting.spi.EligibilityResult;

import java.util.List;
import java.util.UUID;

/**
 * One row of the group list and the head of the group detail (design-core.md §11, design-ux-ops.md
 * §4). A DTO, never a JPA entity (FR-SEC-02, AC-SEC-04). {@code id} is the {@code spot_group} id and
 * is null for a group XamOps has no row for yet.
 *
 * <p><strong>Savings.</strong> {@code monthlySavings} is a number only when every price input was
 * priced; otherwise it is null and {@code savingsUnknown} is true with {@code savingsLabel}
 * {@code "Not priced"}. An unknown price is never 0 (AC-PLN-09, FR-PRC-02). A group with no savings
 * to show (no profile runs Spot at its size) has {@code savingsUnknown=false} and a null amount.
 *
 * @param blockers     the eligibility blockers (Enable is disabled when any exists, with its reason)
 * @param warnings     the eligibility warnings (each needs acknowledgment at confirm)
 * @param deferred     checks LIST mode did not run, shown as "Checked at plan"
 * @param enableBlockedBy the code of the first blocker, or null when Enable is available
 */
public record GroupRowDto(
        UUID id,
        String provider,
        String groupKey,
        String location,
        String name,
        String state,
        boolean managed,
        boolean externalSpot,
        Integer desired,
        Integer min,
        Integer max,
        String instanceType,
        boolean enableAllowed,
        String enableBlockedBy,
        List<CheckDto> blockers,
        List<CheckDto> warnings,
        List<CheckDto> deferred,
        Double monthlySavings,
        boolean savingsUnknown,
        String savingsLabel) {

    /** The label shown when a price is unknown (never 0). */
    public static final String NOT_PRICED = "Not priced";
    /** The label on a priced figure. */
    public static final String ESTIMATE = "Estimate";

    public GroupRowDto {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
    }

    /** A check as the UI sees it. */
    public record CheckDto(String id, String code, String text, String errorKind) {
        public static CheckDto from(EligibilityResult.Check c) {
            return new CheckDto(c.id(), c.code(), c.text(), c.errorKind() == null ? null : c.errorKind().name());
        }
    }

    /**
     * Build a row. {@code monthlySavings} null with {@code savingsApplicable} true means the price is
     * unknown ("Not priced"); null with {@code savingsApplicable} false means there is nothing to show.
     */
    public static GroupRowDto of(UUID id, String provider, String groupKey, String location, String name,
                                 String state, boolean managed, boolean externalSpot, Integer desired, Integer min,
                                 Integer max, String instanceType, EligibilityResult eligibility,
                                 Double monthlySavings, boolean savingsApplicable) {
        boolean unknown = savingsApplicable && monthlySavings == null;
        String blockedBy = eligibility.blockers().isEmpty() ? null : eligibility.blockers().get(0).code();
        return new GroupRowDto(id, provider, groupKey, location, name, state, managed, externalSpot, desired, min,
                max, instanceType, eligibility.eligible(), blockedBy,
                eligibility.blockers().stream().map(CheckDto::from).toList(),
                eligibility.warnings().stream().map(CheckDto::from).toList(),
                eligibility.deferred().stream().map(CheckDto::from).toList(),
                unknown ? null : monthlySavings, unknown,
                unknown ? NOT_PRICED : (monthlySavings == null ? null : ESTIMATE));
    }
}
