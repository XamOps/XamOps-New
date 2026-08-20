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
 * Covers the service-cost lookups that replaced invented constants in the waste detectors.
 *
 * <p>The important property is that a failed or empty lookup falls back to a documented list
 * price rather than returning zero. A zero would be read downstream as "this resource is
 * free" and erase a real cost from the waste total — the same class of bug as pricing lookups
 * returning {@code 0.0} on failure.
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
    @DisplayName("an empty price list falls back to list price, never to zero")
    void emptyPriceListFallsBackNotToZero() {
        when(pricingClient.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(List.of()).build());

        assertThat(service.getEfsGbMonthPrice("eu-west-1")).isEqualTo(0.30);
        assertThat(service.getDynamoDbGbMonthPrice("eu-west-1")).isEqualTo(0.25);
        assertThat(service.getEcrGbMonthPrice("eu-west-1")).isEqualTo(0.10);
        assertThat(service.getEksClusterHourlyPrice("eu-west-1")).isEqualTo(0.10);
    }

    @Test
    @DisplayName("an API failure falls back to list price, never to zero")
    void apiFailureFallsBackNotToZero() {
        when(pricingClient.getProducts(any(GetProductsRequest.class)))
                .thenThrow(new RuntimeException("Pricing API unavailable"));

        assertThat(service.getEfsGbMonthPrice("us-east-1")).isEqualTo(0.30);
        assertThat(service.getDynamoDbGbMonthPrice("us-east-1")).isEqualTo(0.25);
        assertThat(service.getEcrGbMonthPrice("us-east-1")).isEqualTo(0.10);
        assertThat(service.getEksClusterHourlyPrice("us-east-1")).isEqualTo(0.10);
    }

    @Test
    @DisplayName("a null or blank region falls back rather than querying with no region")
    void blankRegionFallsBack() {
        assertThat(service.getEfsGbMonthPrice(null)).isEqualTo(0.30);
        assertThat(service.getEfsGbMonthPrice("  ")).isEqualTo(0.30);
    }

    @Test
    @DisplayName("a zero-priced first entry is skipped in favour of a real price")
    void zeroPricedEntryIsSkipped() {
        respondWith(priceListEntry(0.0), priceListEntry(0.42));
        assertThat(service.getEfsGbMonthPrice("ap-south-1")).isEqualTo(0.42);
    }
}
