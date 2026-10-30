package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.service.autospotting.core.switches.SwitchOperatorService.SwitchView;

import java.time.Instant;

/**
 * One kill-switch row as the operator {@code GET /switches} returns it (design-core.md §6.6): the
 * scope key, the deployed property value, the DB override, the effective value ({@code property AND
 * NOT db.engaged}), the reason and who changed it when, and the {@code version} the operator echoes
 * back on a {@code PUT} for the optimistic guard.
 *
 * @param scopeKey       e.g. {@code GLOBAL}, {@code CLOUD:aws}, {@code CONVERSIONS:gcp}, {@code ACCOUNT:12}
 * @param propertyValue  the deployed property value, or null for a DB-only scope (per-account, freeze)
 * @param engaged        the DB override
 * @param effectiveValue {@code property AND NOT engaged}, or null when there is no property
 * @param reason         the stored change reason
 * @param changedBy      who last changed it
 * @param changedAt      when
 * @param version        the current version (echo on {@code PUT} as {@code expectedVersion})
 */
public record SwitchDto(
        String scopeKey,
        Boolean propertyValue,
        boolean engaged,
        Boolean effectiveValue,
        String reason,
        String changedBy,
        Instant changedAt,
        long version) {

    public static SwitchDto from(SwitchView view) {
        return new SwitchDto(view.scopeKey(), view.propertyValue(), view.engaged(),
                view.effectiveValue(), view.reason(), view.changedBy(), view.changedAt(), view.version());
    }
}
