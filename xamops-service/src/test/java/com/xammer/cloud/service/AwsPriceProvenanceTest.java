package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the price-provenance contract on {@link AwsPricingService}.
 *
 * <p>This class keeps a 2024 us-east-1 price table, a 16-entry regional multiplier map and a
 * size-suffix estimator, because the Calculator prices hypothetical configurations and an
 * approximation is the product there. The defect was that callers could not tell an approximation
 * from a live Price List rate, so estimates flowed unmarked into statements about a customer's real
 * money — Cloudsitter savings and Cost Explorer gap-filling both did.
 *
 * <p>These tests assert the distinction is now visible. They deliberately exercise only the paths
 * that need no AWS credentials: the two lookups that have no live tier at all, and the enum contract
 * every real-money caller now branches on.
 */
class AwsPriceProvenanceTest {

    private AwsPricingService service() {
        return new AwsPricingService(
                Mockito.mock(AwsClientProvider.class),
                Mockito.mock(CloudAccountRepository.class),
                new ObjectMapper(),
                Mockito.mock(CostMockService.class));
    }

    @Test
    @DisplayName("only a live Price List rate counts as authoritative")
    void onlyPriceListIsAuthoritative() {
        assertThat(AwsPricingService.PriceSource.PRICE_LIST.isAuthoritative()).isTrue();

        // The whole point: none of these may be presented as a customer's actual cost.
        assertThat(AwsPricingService.PriceSource.STATIC_TABLE_2024.isAuthoritative()).isFalse();
        assertThat(AwsPricingService.PriceSource.SIZE_PATTERN_ESTIMATE.isAuthoritative()).isFalse();
        assertThat(AwsPricingService.PriceSource.UNAVAILABLE.isAuthoritative()).isFalse();
    }

    @Test
    @DisplayName("load balancer rates are never authoritative and say why")
    void elbRateIsAlwaysAnEstimate() {
        var quote = service().getElbHourlyRateQuote("application");

        assertThat(quote.hasRate()).isTrue();
        assertThat(quote.usdPerHour()).isEqualTo(0.0225);
        // Hardcoded us-east-1 rate, no regional dimension, no live lookup behind it.
        assertThat(quote.isAuthoritative()).isFalse();
        assertThat(quote.source()).isEqualTo(AwsPricingService.PriceSource.STATIC_TABLE_2024);
        // A caller showing this to a user needs to know LCU charges are excluded.
        assertThat(quote.note()).contains("LCU");
    }

    @Test
    @DisplayName("an unknown load balancer type still reports its provenance rather than throwing")
    void unknownElbTypeStillQuoted() {
        var quote = service().getElbHourlyRateQuote(null);

        assertThat(quote.hasRate()).isTrue();
        assertThat(quote.isAuthoritative()).isFalse();
    }

    @Test
    @DisplayName("EBS period cost is never authoritative")
    void ebsCostIsAlwaysAnEstimate() {
        var quote = service().getEbsCostQuoteForPeriod("gp3", 100, "us-east-1", 30);

        assertThat(quote.hasRate()).isTrue();
        // 100 GiB gp3 at the 2024 us-east-1 rate of $0.08/GB-month, multiplier 1.0.
        assertThat(quote.usdPerHour()).isEqualTo(8.0);
        assertThat(quote.isAuthoritative()).isFalse();
        assertThat(quote.source()).isEqualTo(AwsPricingService.PriceSource.STATIC_TABLE_2024);
    }

    @Test
    @DisplayName("an unrecognised volume type is named in the note rather than silently priced as gp3")
    void unrecognisedVolumeTypeIsDisclosed() {
        var quote = service().getEbsCostQuoteForPeriod("gp4-imaginary", 100, "us-east-1", 30);

        // It still returns a number — the Calculator needs one — but the substitution is stated.
        assertThat(quote.hasRate()).isTrue();
        assertThat(quote.note()).contains("gp4-imaginary");
        assertThat(quote.note()).contains("unrecognised");
    }

    @Test
    @DisplayName("a region with no multiplier is disclosed instead of being priced as N. Virginia")
    void unmappedRegionIsDisclosed() {
        // me-central-1 is absent from REGIONAL_MULTIPLIERS, so it silently took 1.0.
        var quote = service().getEbsCostQuoteForPeriod("gp3", 100, "me-central-1", 30);

        assertThat(quote.note()).contains("me-central-1");
        assertThat(quote.note()).contains("unscaled");
    }

    @Test
    @DisplayName("invalid input yields UNAVAILABLE, not a zero that reads as free")
    void invalidInputIsUnavailable() {
        var quote = service().getEbsCostQuoteForPeriod(null, 0, null, 0);

        assertThat(quote.source()).isEqualTo(AwsPricingService.PriceSource.UNAVAILABLE);
        assertThat(quote.hasRate()).isFalse();
        assertThat(quote.isAuthoritative()).isFalse();
    }

    @Test
    @DisplayName("a missing instance type or region is UNAVAILABLE rather than a guessed rate")
    void missingEc2InputIsUnavailable() {
        var quote = service().getHourlyCostQuote(null, "us-east-1");

        assertThat(quote.source()).isEqualTo(AwsPricingService.PriceSource.UNAVAILABLE);
        assertThat(quote.hasRate()).isFalse();
    }

    @Test
    @DisplayName("a saving cannot be produced from an unavailable rate")
    void savingsRequireARate() {
        var quote = service().calculateMonthlySavingsQuote(null, "us-east-1", 300);

        assertThat(quote.source()).isEqualTo(AwsPricingService.PriceSource.UNAVAILABLE);
        assertThat(quote.hasRate()).isFalse();
        assertThat(quote.note()).contains("cannot be calculated");
    }
}
