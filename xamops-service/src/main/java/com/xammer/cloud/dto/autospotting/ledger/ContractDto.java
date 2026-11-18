package com.xammer.cloud.dto.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.ledger.AutoSpottingContract;
import java.time.Instant;
import java.util.UUID;

/**
 * A contract as the BillOps admin sees it. Percentages are decimal strings (never JSON numbers, so no
 * reader turns them into a float); the cap is an {@link AmountDto} (string amount plus currency code),
 * written by the DTO itself so it does not depend on which Jackson generation serializes the response.
 * {@code declaredDiscountPct} null means "not declared", {@code "0"} means "declared none".
 *
 * @param inForce whether the contract is in force right now
 */
public record ContractDto(UUID id, Long clientId, String sharePct, AmountDto monthlyCap,
                          String declaredDiscountPct, String discountDeclaration, Instant effectiveFrom,
                          Instant effectiveTo, String createdBy, Instant createdAt, boolean inForce) {

    public static ContractDto from(AutoSpottingContract c, Instant now) {
        AmountDto cap = c.getMonthlyCap() == null ? null
                : new AmountDto(c.getMonthlyCap().toPlainString(), c.getCapCurrency());
        return new ContractDto(c.getId(), c.getClientId(), c.getSharePct().toPlainString(), cap,
                c.getDeclaredDiscountPct() == null ? null : c.getDeclaredDiscountPct().toPlainString(),
                c.getDeclaredDiscountPct() == null ? "NOT_DECLARED" : "DECLARED",
                c.getEffectiveFrom(), c.getEffectiveTo(), c.getCreatedBy(), c.getCreatedAt(), c.inForceAt(now));
    }

    /** A money amount on the wire: the decimal as a string, never a JSON number, and its currency code. */
    public record AmountDto(String amount, String currency) {
    }
}
