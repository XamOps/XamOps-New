package com.xammer.billops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.dto.InvoiceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail bill is rendered from a DTO that has been through JSON twice.
 *
 * <p>The page does not hand the server an invoice id and ask for a PDF. It re-reads
 * {@code GET /api/admin/invoices/{id}}, which serializes {@link InvoiceDto}, and posts
 * that JSON body back to {@code POST /api/billops/invoices/download-detail}, which
 * deserializes it into a fresh {@code InvoiceDto} and renders from that.
 *
 * <p>So anything the bill prints has to survive a serialize/deserialize round trip. A
 * field with a getter but no setter makes it out and never comes back — and the failure is
 * quiet: the PDF renders, with the field simply absent. The applied rate reaching the
 * description is exactly that kind of field, and this is the only place in the chain where
 * it can be lost without an error.
 */
class DetailBillDtoRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    /** A repriced report line: CUR's own rate in the text, an admin's rate applied. */
    private static InvoiceDto pricedInvoice() {
        InvoiceDto.LineItemDto li = new InvoiceDto.LineItemDto();
        li.setId(1L);
        li.setServiceName("Amazon Elastic Compute Cloud");
        li.setRegionName("ap-south-1");
        li.setUsageType("APS3-NatGateway-Hours");
        li.setResourceName("$0.056 per NAT Gateway Hour");
        li.setUsageQuantity("1422");
        li.setUnit("Hrs");
        li.setCost(new BigDecimal("62.5680"));
        li.setPrivateUnitRate(new BigDecimal("0.044"));
        li.setPrivateRateUnit("Hrs");
        li.setOriginalCost(new BigDecimal("79.6320"));
        li.setDiscountShielded(true);

        InvoiceDto dto = new InvoiceDto();
        dto.setId(166L);
        dto.setBillingPeriod("2026-09");
        dto.setLineItems(java.util.List.of(li));
        dto.setPreDiscountTotal(new BigDecimal("62.5680"));
        dto.setPrivatePricedTotal(new BigDecimal("62.5680"));
        dto.setDiscountShieldedTotal(new BigDecimal("62.5680"));
        return dto;
    }

    private InvoiceDto roundTrip(InvoiceDto dto) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(dto), InvoiceDto.class);
    }

    @Test
    @DisplayName("the applied rate survives the round trip the detail bill makes")
    void appliedRateSurvivesTheRoundTrip() throws Exception {
        InvoiceDto.LineItemDto line = roundTrip(pricedInvoice()).getLineItems().get(0);

        assertThat(line.getPrivateUnitRate()).isEqualByComparingTo("0.044");
        assertThat(line.getPrivateRateUnit()).isEqualTo("Hrs");
        assertThat(line.getOriginalCost()).isEqualByComparingTo("79.6320");
        assertThat(line.isDiscountShielded()).isTrue();
        assertThat(line.isPrivatelyPriced()).isTrue();
    }

    /**
     * The whole point of the round trip surviving: what the customer reads on the bill.
     * Before the rate was carried through, the line printed the provider's "$0.056 per NAT
     * Gateway Hour" against an amount computed at $0.044 — 1,422 x $0.056 is $79.63, not
     * the $62.57 beside it.
     */
    @Test
    @DisplayName("the bill line prints the rate billed, after the round trip")
    void theBillLinePrintsTheRateBilledAfterTheRoundTrip() throws Exception {
        InvoiceDto.LineItemDto line = roundTrip(pricedInvoice()).getLineItems().get(0);

        String description = InvoiceManagementService.detailLineDescription(line);

        assertThat(description).isEqualTo("NAT Gateway Hour @ $0.044 per Hrs");
        assertThat(description).doesNotContain("0.056");

        // Printed rate x printed quantity is the printed amount.
        assertThat(new BigDecimal("0.044").multiply(new BigDecimal(line.getUsageQuantity())))
                .isEqualByComparingTo(line.getCost());
    }

    /**
     * A derived property with no setter must not be serialized at all.
     *
     * <p>{@code privatelyPriced} on a line and {@code margin} on a rate are both computed
     * from fields already on the wire. Sending them anyway made the round trip depend on
     * FAIL_ON_UNKNOWN_PROPERTIES being off — true under Boot's defaults, so the bill kept
     * rendering, but one config change away from every detail bill download answering 400.
     * This test is deliberately run through a plain ObjectMapper, which has Jackson's own
     * default of failing, so the dependency cannot creep back.
     */
    @Test
    @DisplayName("derived, setter-less properties are not serialized at all")
    void derivedPropertiesDoNotBreakDeserialization() throws Exception {
        InvoiceDto.PrivatePricingDto pricing = new InvoiceDto.PrivatePricingDto();
        pricing.setId(42L);
        pricing.setServiceName("Amazon Elastic Compute Cloud");
        pricing.setScopeLabel("Amazon Elastic Compute Cloud · ap-south-1 · APS3-NatGateway-Hours");
        pricing.setUnitRate(new BigDecimal("0.044"));
        pricing.setRateUnit("Hrs");
        pricing.setExcludeFromOverallDiscount(true);
        pricing.setOriginalAmount(new BigDecimal("79.6320"));
        pricing.setPricedAmount(new BigDecimal("62.5680"));

        InvoiceDto dto = pricedInvoice();
        dto.setPrivatePricing(java.util.List.of(pricing));

        String json = mapper.writeValueAsString(dto);
        // Neither is on the wire: both are derived, neither has a setter, and both were
        // breaking the round trip until they were marked @JsonIgnore.
        assertThat(json).doesNotContain("\"privatelyPriced\"").doesNotContain("\"margin\"");

        InvoiceDto back = roundTrip(dto);
        assertThat(back.getPrivatePricing()).hasSize(1);
        assertThat(back.getPrivatePricing().get(0).getUnitRate()).isEqualByComparingTo("0.044");
        assertThat(back.getLineItems().get(0).getPrivateUnitRate()).isEqualByComparingTo("0.044");
    }

    @Test
    @DisplayName("a line never repriced keeps its description through the round trip")
    void anUnpricedLineIsUnaffected() throws Exception {
        InvoiceDto dto = pricedInvoice();
        InvoiceDto.LineItemDto li = dto.getLineItems().get(0);
        li.setPrivateUnitRate(null);
        li.setPrivateRateUnit(null);
        li.setOriginalCost(null);
        li.setCost(new BigDecimal("79.6320"));

        InvoiceDto.LineItemDto back = roundTrip(dto).getLineItems().get(0);

        assertThat(back.isPrivatelyPriced()).isFalse();
        assertThat(InvoiceManagementService.detailLineDescription(back))
                .isEqualTo("$0.056 per NAT Gateway Hour");
    }
}
