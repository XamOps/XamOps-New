package com.xammer.cloud.dto.autospotting.ledger;

import java.math.BigDecimal;
import java.time.Instant;

/** Request bodies of the BillOps contract and go-live endpoints (design-ledger.md §11.4). */
public final class ContractRequests {

    private ContractRequests() {
    }

    /** {@code POST /clients/{clientId}/contracts}. {@code effectiveFrom} omitted means now. */
    public record Create(BigDecimal sharePct, BigDecimal monthlyCap, String capCurrency,
                         String discountDeclaration, BigDecimal declaredDiscountPct,
                         Instant effectiveFrom, Instant effectiveTo, String reason) {
    }

    /** {@code POST /contracts/{id}/end}. {@code effectiveTo} omitted means now. */
    public record End(Instant effectiveTo, String reason) {
    }

    /** {@code POST /golive/{provider}}. {@code goLiveAt} omitted means now. */
    public record GoLive(Instant goLiveAt, String reason) {
    }
}
