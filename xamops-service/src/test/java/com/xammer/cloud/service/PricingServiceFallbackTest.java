package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the service-cost lookups behind the waste detectors.
 *
 * <p>These tests previously asserted that a failed lookup fell back to a documented us-east-1 list
 * price, on the reasoning that returning zero would be read downstream as "this resource is free"
 * and erase a real cost from the waste total.
 *
 * <p>That concern was correct, and it is still enforced — but at the detector rather than here.
 * Substituting a constant traded one silent error for another: an EFS filesystem in São Paulo whose
 * SKU lookup failed was reported at the Virginia rate and counted in the savings total as though
 * measured, with nothing to distinguish it from a real figure. The lookups now return
 * {@link PricingService#PRICE_UNAVAILABLE}, and {@code OptimizationService.wasteWithRate} turns that
 * into a finding flagged {@code savingsUnknown} — so the resource is still surfaced (the original
 * concern) while being excluded from the total instead of contributing a fabricated number.
 *
 * <p>{@code UnpricedFigureContractTest} guards the other half: that an unpriced finding is still
 * reported and is left out of sums.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingServiceFallbackTest {

    @Mock PricingClient pricingClient;
    @Mock AwsClientProvider awsClientProvider;
    @Mock CostMockService costMockService;

    private PricingService service;

    /** A minimal Price List entry with a single OnDemand dimension. */
    private static String priceListEntry(double usdPerUnit) {
        return """
               {"terms":{"OnDemand":{"sku.term":{"priceDimensions":{"sku.term.dim":{
               "pricePerUnit":{"USD":"%s"}}}}}}}
               """.formatted(usdPerUnit);
    }

    @BeforeEach
    void setUp() {
        service = new PricingService(pricingClient, awsClientProvider, costMockService);
    }

    private void respondWith(String... entries) {
        when(pricingClient.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(List.of(entries)).build());
    }

    @Test
    @DisplayName("EFS resolves the live regional GB-month rate")
    void efsUsesLivePrice() {
        respondWith(priceListEntry(0.36));
        assertThat(service.getEfsGbMonthPrice("ap-south-1")).isEqualTo(0.36);
    }

    @Test
    @DisplayName("DynamoDB resolves the live regional GB-month rate")
    void dynamoDbUsesLivePrice() {
        respondWith(priceListEntry(0.285));
        assertThat(service.getDynamoDbGbMonthPrice("ap-south-1")).isEqualTo(0.285);
    }

    @Test
    @DisplayName("ECR resolves the live regional GB-month rate")
    void ecrUsesLivePrice() {
        respondWith(priceListEntry(0.11));
        assertThat(service.getEcrGbMonthPrice("ap-south-1")).isEqualTo(0.11);
    }

    @Test
    @DisplayName("EKS resolves the live regional control-plane hourly rate")
    void eksUsesLivePrice() {
        respondWith(priceListEntry(0.10));
        assertThat(service.getEksClusterHourlyPrice("ap-south-1")).isEqualTo(0.10);
    }

    @Test
    @DisplayName("an empty price list reports the price as unavailable, not as a us-east-1 constant")
    void emptyPriceListReportsUnavailable() {
        when(pricingClient.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(List.of()).build());

        // eu-west-1 rates differ from us-east-1, so answering with the Virginia constant was wrong
        // in a way no caller could detect.
        assertThat(PricingService.isPriceAvailable(service.getEfsGbMonthPrice("eu-west-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getDynamoDbGbMonthPrice("eu-west-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getEcrGbMonthPrice("eu-west-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getEksClusterHourlyPrice("eu-west-1"))).isFalse();
    }

    @Test
    @DisplayName("an API failure reports the price as unavailable")
    void apiFailureReportsUnavailable() {
        when(pricingClient.getProducts(any(GetProductsRequest.class)))
                .thenThrow(new RuntimeException("Pricing API unavailable"));

        assertThat(PricingService.isPriceAvailable(service.getEfsGbMonthPrice("us-east-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getDynamoDbGbMonthPrice("us-east-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getEcrGbMonthPrice("us-east-1"))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getEksClusterHourlyPrice("us-east-1"))).isFalse();
    }

    @Test
    @DisplayName("a null or blank region reports unavailable rather than querying with no region")
    void blankRegionReportsUnavailable() {
        assertThat(PricingService.isPriceAvailable(service.getEfsGbMonthPrice(null))).isFalse();
        assertThat(PricingService.isPriceAvailable(service.getEfsGbMonthPrice("  "))).isFalse();
    }

    @Test
    @DisplayName("PRICE_UNAVAILABLE is not mistaken for a free resource")
    void unavailableIsNotFree() {
        // The sentinel is 0.0 for backward compatibility, so the guard is the only thing separating
        // "could not price" from "costs nothing". Callers must use it rather than comparing to zero.
        assertThat(PricingService.isPriceAvailable(PricingService.PRICE_UNAVAILABLE)).isFalse();
        assertThat(PricingService.isPriceAvailable(0.01)).isTrue();
    }

    @Test
    @DisplayName("a zero-priced first entry is skipped in favour of a real price")
    void zeroPricedEntryIsSkipped() {
        respondWith(priceListEntry(0.0), priceListEntry(0.42));
        assertThat(service.getEfsGbMonthPrice("ap-south-1")).isEqualTo(0.42);
    }
}
