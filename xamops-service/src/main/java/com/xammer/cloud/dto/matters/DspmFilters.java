package com.xammer.cloud.dto.matters;

import java.util.List;
import java.util.Map;

/**
 * Filter options with counts, for the filter rail.
 *
 * <p>Counts are <b>disjunctive</b>, which is a property of the upstream endpoint and is worth relying
 * on rather than working around: each dimension's counts reflect every <em>other</em> active filter but
 * not its own, so selecting {@code provider=S3} leaves the provider list showing all nine providers at
 * full counts (letting a user see what picking {@code EBS} instead would yield) while severity narrows
 * to S3 assets only. Verified live. {@code category} is the exception — it always applies, to every
 * dimension.
 *
 * <p>Consequence for the UI: call this endpoint with exactly the same filter parameters as the list, and
 * the rail stays populated instead of collapsing to only the selected value.
 *
 * @param dimensions dimension name → its options, ordered by count descending. Nine dimensions are
 *                   returned live, three of them undocumented: {@code data_element} (the specific
 *                   detector, and the most useful one for DSPM), {@code lifecycle}, and the
 *                   {@code paging} object which is lifted out into {@link #hasMore} rather than
 *                   appearing here as a pseudo-dimension.
 * @param hasMore    dimension name → whether its option list was truncated upstream. Derived from the
 *                   undocumented {@code paging} object, which carries a {@code cursor} and
 *                   {@code has_more} per dimension. Everything is {@code false} on a small project, but
 *                   a large customer will have truncated lists, and a rail that silently omits providers
 *                   is worse than one that admits it is incomplete. There is no cursor parameter on the
 *                   list endpoint to follow it with, so the honest move is to surface the flag.
 */
public record DspmFilters(
        Map<String, List<Option>> dimensions,
        Map<String, Boolean> hasMore
) {

    /**
     * @param value raw value to send back as a filter parameter. For severity this may be the
     *              {@code NONE} sentinel, which corresponds to rows whose {@code risk.level} is null.
     * @param label display wording.
     * @param count assets matching, under the other active filters.
     */
    public record Option(String value, String label, long count) {
    }
}
