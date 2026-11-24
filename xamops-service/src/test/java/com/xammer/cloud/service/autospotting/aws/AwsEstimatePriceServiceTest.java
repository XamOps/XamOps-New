package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.Platform;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PriceKey;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PriceStatus;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PricedRate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** plan-phase-12.md 12.4: AC-PRC-01, AC-PRC-04, AC-AWS-03; design-aws.md §7.5. Mocked SDK clients only. */
class AwsEstimatePriceServiceTest {

    private static final String PRODUCT = "{\"product\":{\"sku\":\"SKU1\"},\"serviceCode\":\"AmazonEC2\","
            + "\"terms\":{\"OnDemand\":{\"SKU1.JRTC\":{\"priceDimensions\":{\"SKU1.JRTC.6YS\":{\"unit\":\"Hrs\","
            + "\"pricePerUnit\":{\"USD\":\"0.0960000000\"}}}}}},\"version\":\"20261101\","
            + "\"publicationDate\":\"2026-11-01T00:00:00Z\"}";

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-17T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final PricingClient pricing = mock(PricingClient.class);
    private final FakeAws aws = new FakeAws().happyPath(FakeAws.asg("web-asg").build());
    private final MutableClock clock = new MutableClock();
    private final AwsEstimatePriceService service =
            new AwsEstimatePriceService(pricing, (acct, region) -> aws.view(), clock);
    private final CloudAccount account = FakeAws.account();

    private static PriceKey key(Platform p) {
        return new PriceKey("ap-south-1", null, "m5.large", p, "Shared");
    }

    private void priceListReturns(List<String> products) {
        when(pricing.getProducts(any(GetProductsRequest.class)))
                .thenReturn(GetProductsResponse.builder().priceList(products).build());
    }

    @Test
    void anOnDemandRateIsPricedWithProvenanceAndCached() {
        priceListReturns(List.of(PRODUCT));
        PricedRate r = service.onDemand(account, key(Platform.LINUX));
        assertThat(r.status()).isEqualTo(PriceStatus.PRICED);
        assertThat(r.ratePerHour()).isEqualByComparingTo(new BigDecimal("0.096"));
        assertThat(r.source()).isEqualTo("aws-price-list");
        assertThat(r.sourceVersion()).isEqualTo("20261101");
        assertThat(r.asOf()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        service.onDemand(account, key(Platform.LINUX));
        verify(pricing, times(1)).getProducts(any(GetProductsRequest.class));
    }

    @Test
    void anUnmatchedSkuIsNotPricedAndNotCached() {
        priceListReturns(List.of());
        assertThat(service.onDemand(account, key(Platform.LINUX)).status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(service.onDemand(account, key(Platform.LINUX)).status()).isEqualTo(PriceStatus.NOT_PRICED);
        verify(pricing, times(2)).getProducts(any(GetProductsRequest.class));
        // Two products is ambiguous: also Not priced, never a guess.
        priceListReturns(List.of(PRODUCT, PRODUCT));
        assertThat(service.onDemand(account, new PriceKey("ap-south-1", null, "c5.large", Platform.LINUX, "Shared"))
                .status()).isEqualTo(PriceStatus.NOT_PRICED);
    }

    @Test
    void aFailedCallIsPricingUnavailableNotCachedAndBacksOffThenRetries() {
        when(pricing.getProducts(any(GetProductsRequest.class))).thenThrow(SdkClientException.create("boom"));
        PricedRate first = service.onDemand(account, key(Platform.LINUX));
        assertThat(first.status()).isEqualTo(PriceStatus.PRICING_UNAVAILABLE);
        assertThat(first.ratePerHour()).isNull();
        // Inside the 1 minute backoff the API is not called again.
        assertThat(service.onDemand(account, key(Platform.LINUX)).status()).isEqualTo(PriceStatus.PRICING_UNAVAILABLE);
        verify(pricing, times(1)).getProducts(any(GetProductsRequest.class));
        // After the backoff the next lookup hits the API again, and a success is then priced.
        clock.advance(Duration.ofMinutes(2));
        priceListReturns(List.of(PRODUCT));
        assertThat(service.onDemand(account, key(Platform.LINUX)).status()).isEqualTo(PriceStatus.PRICED);
        verify(pricing, times(2)).getProducts(any(GetProductsRequest.class));
    }

    @Test
    void theBackoffDoublesToAThirtyMinuteCap() {
        when(pricing.getProducts(any(GetProductsRequest.class))).thenThrow(SdkClientException.create("boom"));
        int calls = 0;
        // 1, 2, 4, 8, 16, 30, 30 minutes: advance just past each window and count the retries.
        for (long minutes : new long[] {1, 2, 4, 8, 16, 30, 30}) {
            service.onDemand(account, key(Platform.LINUX));
            calls++;
            clock.advance(Duration.ofMinutes(minutes).plusSeconds(1));
        }
        verify(pricing, times(calls)).getProducts(any(GetProductsRequest.class));
        // One tenant's failures never delay another's.
        CloudAccount other = FakeAws.account();
        other.setAwsAccountId("999988887777");
        priceListReturns(List.of(PRODUCT));
        assertThat(service.onDemand(other, key(Platform.LINUX)).status()).isEqualTo(PriceStatus.PRICED);
    }

    @Test
    void windowsSuseAndRhelPickTheMatchingProduct() {
        priceListReturns(List.of(PRODUCT));
        Map<Platform, String[]> expected = Map.of(
                Platform.WINDOWS, new String[] {"Windows", "NA", "License Included"},
                Platform.SUSE, new String[] {"SUSE", "NA", "No License required"},
                Platform.RHEL, new String[] {"RHEL", "NA", "No License required"},
                Platform.WINDOWS_SQL_STANDARD, new String[] {"Windows", "SQL Std", "License Included"});
        for (var e : expected.entrySet()) {
            service.onDemand(account, key(e.getKey()));
        }
        ArgumentCaptor<GetProductsRequest> cap = ArgumentCaptor.forClass(GetProductsRequest.class);
        verify(pricing, times(expected.size())).getProducts(cap.capture());
        for (GetProductsRequest r : cap.getAllValues()) {
            Map<String, String> f = new java.util.HashMap<>();
            r.filters().forEach(x -> f.put(x.field(), x.value()));
            assertThat(f).containsEntry("regionCode", "ap-south-1").containsEntry("instanceType", "m5.large")
                    .containsEntry("tenancy", "Shared").containsEntry("capacitystatus", "Used");
            String[] want = expected.values().stream().filter(w -> w[0].equals(f.get("operatingSystem"))
                    && w[1].equals(f.get("preInstalledSw")) && w[2].equals(f.get("licenseModel"))).findFirst().orElse(null);
            assertThat(want).as("filters %s", f).isNotNull();
        }
    }

    @Test
    void anUnmappedPlatformIsNotPricedWithoutCallingTheApi() {
        assertThat(AwsEstimatePriceService.platformOf("Windows with SQL Server Standard"))
                .isEqualTo(Platform.WINDOWS_SQL_STANDARD);
        assertThat(AwsEstimatePriceService.platformOf("Linux/UNIX")).isEqualTo(Platform.LINUX);
        assertThat(AwsEstimatePriceService.platformOf("Ubuntu Pro")).isEqualTo(Platform.UNKNOWN);
        assertThat(AwsEstimatePriceService.platformOf(null)).isEqualTo(Platform.UNKNOWN);
        assertThat(service.onDemand(account, key(Platform.UNKNOWN)).status()).isEqualTo(PriceStatus.NOT_PRICED);
        verify(pricing, times(0)).getProducts(any(GetProductsRequest.class));
    }

    @Test
    void aSpotRateComesFromTheCustomerSessionAndIsCachedForAnHour() {
        PriceKey k = new PriceKey("ap-south-1", FakeAws.AZ_A, "m5.large", Platform.LINUX, "Shared");
        PricedRate r = service.spot(account, k);
        assertThat(r.status()).isEqualTo(PriceStatus.PRICED);
        assertThat(r.ratePerHour()).isPositive();
        assertThat(r.source()).isEqualTo("ec2-spot-price-history");
        assertThat(r.asOf()).isNotNull();
        service.spot(account, k);
        assertThat(aws.calls("describeSpotPriceHistory")).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        aws.assertReadOnly();
    }

    @Test
    void aFailedSpotLookupIsUnavailableNotZeroAndNotCached() {
        doThrow(SdkClientException.create("boom")).when(aws.ec2).describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class));
        PriceKey k = new PriceKey("ap-south-1", FakeAws.AZ_A, "m5.large", Platform.LINUX, "Shared");
        PricedRate r = service.spot(account, k);
        assertThat(r.status()).isEqualTo(PriceStatus.PRICING_UNAVAILABLE);
        assertThat(r.ratePerHour()).isNull();
        // The sql editions and an AZ-less key have no spot history: Not priced.
        assertThat(service.spot(account, new PriceKey("ap-south-1", null, "m5.large", Platform.LINUX, "Shared")).status())
                .isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(service.spot(account, new PriceKey("ap-south-1", FakeAws.AZ_A, "m5.large",
                Platform.WINDOWS_SQL_WEB, "Shared")).status()).isEqualTo(PriceStatus.NOT_PRICED);
    }

    @Test
    void theMedianAcrossTheGroupsAzsIsPerType() {
        Map<String, BigDecimal> medians = service.medianSpotByType(account, "ap-south-1",
                List.of(FakeAws.AZ_A, FakeAws.AZ_B), List.of("m5.large", "m5a.large"), Platform.LINUX);
        assertThat(medians).containsOnlyKeys("m5.large", "m5a.large");
        assertThat(medians.get("m5.large")).isPositive();
    }
}
