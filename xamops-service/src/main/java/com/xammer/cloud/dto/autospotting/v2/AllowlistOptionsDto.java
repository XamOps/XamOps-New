package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.service.autospotting.core.allowlist.AllowlistOperatorService.BasisOptions;

import java.util.UUID;

/** The billing bases the Add dialog may offer for one account (design-core.md §6.1). */
public record AllowlistOptionsDto(long cloudAccountId, String provider, String accountName, Long clientId,
                                  boolean contractInForce, UUID contractId) {

    public static AllowlistOptionsDto from(BasisOptions o) {
        return new AllowlistOptionsDto(o.cloudAccountId(), o.provider(), o.accountName(), o.clientId(),
                o.contractInForce(), o.contractId());
    }
}
