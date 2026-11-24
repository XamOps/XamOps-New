package com.xammer.cloud.dto.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.SavingsLedgerGoLive;

import java.time.Instant;

/** The go-live instant of one provider (design-ledger.md §2.3). */
public record GoLiveDto(String provider, Instant goLiveAt, String setBy, Instant setAt) {

    public static GoLiveDto from(SavingsLedgerGoLive g) {
        return new GoLiveDto(g.getProvider(), g.getGoLiveAt(), g.getSetBy(), g.getSetAt());
    }
}
