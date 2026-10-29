package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What an uploaded AWS bill PDF says, shown to the admin before a draft is created from it.
 *
 * <p>{@code account} is the BillOps account the PDF's account ID resolved to, or null when
 * none is set up (or the admin may not see it). When none is set up,
 * {@code accountWillBeCreated} is true and the draft adds it. Year and month are null when
 * the PDF printed no billing period.
 */
public record UploadedBillPreviewDto(
        String fileName,
        int pageCount,
        String accountId,
        MatchedAccount account,
        /** The bill's account isn't in BillOps; creating the draft will add it. */
        boolean accountWillBeCreated,
        /** Name the new account gets unless the admin types another. */
        String suggestedAccountName,
        String billingPeriod,
        Integer year,
        Integer month,
        LocalDate datePrinted,
        boolean estimated,
        BigDecimal grandTotal,
        BigDecimal preTaxTotal,
        BigDecimal taxTotal,
        BigDecimal marketplaceTotal,
        BigDecimal lineItemTotal,
        int lineItemCount,
        /** Lines added so every subtotal matches the bill; see ParsedAwsBill.ROUNDING_DESCRIPTION. */
        long roundingLineCount,
        BigDecimal roundingTotal,
        List<ServiceSummary> services,
        List<String> warnings) {

    public record MatchedAccount(Long id, String name, String identifier, String provider) {
    }

    public record ServiceSummary(String serviceName, BigDecimal total, int lineCount) {
    }
}
