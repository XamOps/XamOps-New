package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.AllowlistView;

import java.time.Instant;
import java.util.UUID;

/**
 * One allowlist row for the operator view (design-core.md §6.6): the billing basis, the recorded
 * contract and whether it is still in force. Never an entity (AC-SEC-04).
 */
public record AllowlistDto(long cloudAccountId, String provider, String accountName, Long clientId,
                           String billingBasis, UUID contractId, boolean contractInForce, String reason,
                           String addedBy, Instant addedAt) {

    public static AllowlistDto from(AllowlistView v) {
        return new AllowlistDto(v.cloudAccountId(), v.provider(), v.accountName(), v.clientId(),
                v.billingBasis(), v.contractId(), v.contractInForce(), v.reason(), v.addedBy(), v.addedAt());
    }
}
