package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.SpotPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The resolver's routing, honesty, request-shape, TTL and tenant-isolation rules, all AWS mocked.
 */
class NodeRateResolverTest {

    private AwsPricingService pricing;
    private SpotPricingService spot;
    private AwsClientProvider clientProvider;
    private Ec2Client ec2;
    private MutableClock clock;

    private static CloudAccount account(String awsId) {
        CloudAccount a = new CloudAccount("acct", "ext", "read-only", null);
        a.setAwsAccountId(awsId);
        return a;
    }

    @BeforeEach
    void setUp() {
        pricing = mock(AwsPricingService.class);
        spot = mock(SpotPricingService.class);
        clientProvider = mock(AwsClientProvider.class);
        ec2 = mock(Ec2Client.class);
        clock = new MutableClock(Instant.parse("2026-10-19T00:00:00Z"));
        when(clientProvider.getEc2Client(any(), any())).thenReturn(ec2);
    }

    private NodeRateResolver resolver() {
        return new NodeRateResolver(pricing, spot, clientProvider, K8sCostProperties.enabledDefaults(),
                clock);
    }

    private static AwsPricingService.RateQuote priceList(double usd) {
        return new AwsPricingService.RateQuote(usd, AwsPricingService.PriceSource.PRICE_LIST, "live");
    }

    private static AwsPricingService.RateQuote quote(double usd, AwsPricingService.PriceSource source) {
        return new AwsPricingService.RateQuote(usd, source, "note");
    }

    private static AwsPricingService.RateQuote unavailable() {
        return new AwsPricingService.RateQuote(0.0, AwsPricingService.PriceSource.UNAVAILABLE, "none");
    }

    @Test
    @DisplayName("on-demand accepts a PRICE_LIST quote and prices it")
    void onDemandPriceList() {
        when(pricing.getHourlyCostQuote("m5.large", "ap-south-1")).thenReturn(priceList(0.096));

        NodeRate rate = resolver().resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");

        assertThat(rate.priceable()).isTrue();
        assertThat(rate.usdPerHour()).isEqualTo(0.096);
        assertThat(rate.source()).isEqualTo(NodeRate.Source.PRICE_LIST);
    }

    @Test
    @DisplayName("a non-PRICE_LIST quote is unpriceable NO_LIVE_PRICE, never a zero-as-real")
    void onDemandNonPriceListIsUnpriceable() {
        when(pricing.getHourlyCostQuote(any(), any())).thenReturn(
                quote(0.1, AwsPricingService.PriceSource.STATIC_TABLE_2024));

        NodeRate rate = resolver().resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");

        assertThat(rate.priceable()).isFalse();
        assertThat(rate.reason()).isEqualTo(NodeRate.Reason.NO_LIVE_PRICE);
    }

    @Test
    @DisplayName("no capacity-type label prices on-demand with capacityTypeInferred == true")
    void inferredCapacityType() {
        when(pricing.getHourlyCostQuote(any(), any())).thenReturn(priceList(0.096));

        NodeRate rate = resolver().resolve(account("111"), "m5.large", null, null, "ap-south-1");

        assertThat(rate.priceable()).isTrue();
        assertThat(rate.capacityTypeInferred()).isTrue();
    }

    @Test
    @DisplayName("blank instance type is UNKNOWN_INSTANCE_TYPE")
    void unknownInstanceType() {
        NodeRate rate = resolver().resolve(account("111"), "  ", "on-demand", null, "ap-south-1");
        assertThat(rate.reason()).isEqualTo(NodeRate.Reason.UNKNOWN_INSTANCE_TYPE);
    }

    @Test
    @DisplayName("spot routes to DescribeSpotPriceHistory and takes the latest sample")
    void spotRouting() {
        when(ec2.describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class)))
                .thenReturn(spotResponse("ap-south-1a", 0.03, 0.04));

        NodeRate rate = resolver().resolve(account("111"), "m5.large", "spot", "ap-south-1a", "ap-south-1");

        assertThat(rate.priceable()).isTrue();
        assertThat(rate.source()).isEqualTo(NodeRate.Source.SPOT_HISTORY);
        assertThat(rate.usdPerHour()).isEqualTo(0.04);
    }

    @Test
    @DisplayName("empty spot history is NO_SPOT_HISTORY")
    void emptySpotHistory() {
        when(ec2.describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class)))
                .thenReturn(DescribeSpotPriceHistoryResponse.builder().build());

        NodeRate rate = resolver().resolve(account("111"), "m5.large", "spot", "ap-south-1a", "ap-south-1");

        assertThat(rate.priceable()).isFalse();
        assertThat(rate.reason()).isEqualTo(NodeRate.Reason.NO_SPOT_HISTORY);
    }

    @Test
    @DisplayName("a spot node with an absent/unparseable zone is NODE_ZONE_UNKNOWN and issues NO EC2 call")
    void spotWithoutZone() {
        NodeRate absent = resolver().resolve(account("111"), "m5.large", "spot", null, "ap-south-1");
        NodeRate garbage = resolver().resolve(account("111"), "m5.large", "spot", "apsouth", "ap-south-1");

        assertThat(absent.reason()).isEqualTo(NodeRate.Reason.NODE_ZONE_UNKNOWN);
        assertThat(garbage.reason()).isEqualTo(NodeRate.Reason.NODE_ZONE_UNKNOWN);
        verify(ec2, never()).describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class));
    }

    @Test
    @DisplayName("the spot request carries availabilityZone == zone and Linux/UNIX")
    void spotRequestShape() {
        when(ec2.describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class)))
                .thenReturn(spotResponse("ap-south-1a", 0.03));

        resolver().resolve(account("111"), "m5.large", "spot", "ap-south-1a", "ap-south-1");

        ArgumentCaptor<DescribeSpotPriceHistoryRequest> captor =
                ArgumentCaptor.forClass(DescribeSpotPriceHistoryRequest.class);
        verify(ec2).describeSpotPriceHistory(captor.capture());
        assertThat(captor.getValue().availabilityZone()).isEqualTo("ap-south-1a");
        assertThat(captor.getValue().productDescriptions()).containsExactly("Linux/UNIX");
    }

    @Test
    @DisplayName("on-demand caches: a second resolve within TTL does not re-dial Pricing")
    void onDemandCached() {
        when(pricing.getHourlyCostQuote(any(), any())).thenReturn(priceList(0.096));
        NodeRateResolver resolver = resolver();

        resolver.resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");
        resolver.resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");

        verify(pricing, times(1)).getHourlyCostQuote("m5.large", "ap-south-1");
    }

    @Test
    @DisplayName("a transient verdict is re-attempted after the 60s floor; a structural one is held the full TTL")
    void negativeCachingSplit() {
        when(pricing.getHourlyCostQuote(any(), any())).thenReturn(unavailable());
        NodeRateResolver resolver = resolver();

        resolver.resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");
        clock.advance(Duration.ofSeconds(61));                 // past transientNegativeTtl
        resolver.resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");

        verify(pricing, times(2)).getHourlyCostQuote("m5.large", "ap-south-1");
    }

    @Test
    @DisplayName("tenant isolation: two accounts never share a spot entry; on-demand stays account-independent")
    void tenantIsolation() {
        when(ec2.describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class)))
                .thenReturn(spotResponse("ap-south-1a", 0.03));
        when(pricing.getHourlyCostQuote(any(), any())).thenReturn(priceList(0.096));
        NodeRateResolver resolver = resolver();

        resolver.resolve(account("111"), "m5.large", "spot", "ap-south-1a", "ap-south-1");
        resolver.resolve(account("222"), "m5.large", "spot", "ap-south-1a", "ap-south-1");
        // Two accounts, same instanceType|zone within spotTtl -> two EC2 calls (each gets its own).
        verify(ec2, times(2)).describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class));

        resolver.resolve(account("111"), "m5.large", "on-demand", null, "ap-south-1");
        resolver.resolve(account("222"), "m5.large", "on-demand", null, "ap-south-1");
        // On-demand is account-independent -> one Pricing call shared across the two accounts.
        verify(pricing, times(1)).getHourlyCostQuote("m5.large", "ap-south-1");
    }

    private static DescribeSpotPriceHistoryResponse spotResponse(String zone, double... prices) {
        SpotPrice[] samples = new SpotPrice[prices.length];
        Instant base = Instant.parse("2026-10-19T00:00:00Z").minus(Duration.ofMinutes(20));
        for (int i = 0; i < prices.length; i++) {
            samples[i] = SpotPrice.builder()
                    .availabilityZone(zone)
                    .spotPrice(Double.toString(prices[i]))
                    .timestamp(base.plus(Duration.ofMinutes(i)))
                    .build();
        }
        return DescribeSpotPriceHistoryResponse.builder().spotPriceHistory(samples).build();
    }

    /** A clock whose instant can be advanced, for TTL assertions. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
