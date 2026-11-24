package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.Platform;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PriceKey;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PriceStatus;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService.PricedRate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pricing from the {@code listSkus} fixture (AC-PRC-03, V-GCP-09). Nothing calls GCP. */
class GcpEstimatePriceServiceTest {
    private static final String ZONE = "us-central1-a";

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(5L);
        a.setGcpProjectId(FakeGcp.PROJECT);
        return a;
    }

    private static GcpEstimatePriceService service(FakeGcp gcp, Clock clock) {
        return new GcpEstimatePriceService(acct -> gcp.view(), new GcpMachineShapes(clock, GcpSharedCoreTable.v1()), clock);
    }

    private static void machine(FakeGcp gcp, String name, int cpus, long memMb, boolean shared) {
        gcp.machineTypes.put(name, FakeGcp.JSON.createObjectNode().put("guestCpus", cpus).put("memoryMb", memMb)
                .put("isSharedCpu", shared));
    }

    private static PriceKey key(String type) {
        return new PriceKey("us-central1", ZONE, type, Platform.LINUX, "Shared");
    }

    @Test
    void c2M1AndM2ArePricedStandardAndSpotFromTheFixture() {
        FakeGcp gcp = FakeGcp.healthy();
        machine(gcp, "c2-standard-8", 8, 32768, false);
        machine(gcp, "m1-ultramem-40", 40, 977 * 1024L, false);
        machine(gcp, "m2-ultramem-208", 208, 5888 * 1024L, false);
        GcpEstimatePriceService svc = service(gcp, Clock.systemUTC());
        CloudAccount a = account();
        // C2: 8 x 0.033174 + 32 x 0.004446
        PricedRate c2od = svc.onDemand(a, key("c2-standard-8"));
        assertThat(c2od.status()).isEqualTo(PriceStatus.PRICED);
        assertThat(c2od.ratePerHour()).isEqualByComparingTo(new BigDecimal("0.265392").add(new BigDecimal("0.142272")));
        // C2 Spot: 8 x 0.008 + 32 x 0.001072
        PricedRate c2spot = svc.spot(a, key("c2-standard-8"));
        assertThat(c2spot.ratePerHour()).isEqualByComparingTo(new BigDecimal("0.064").add(new BigDecimal("0.034304")));
        assertThat(c2spot.ratePerHour()).isLessThan(c2od.ratePerHour());
        // M1: 40 x 0.03475 + 977 x 0.004655
        assertThat(svc.onDemand(a, key("m1-ultramem-40")).ratePerHour())
                .isEqualByComparingTo(new BigDecimal("1.39").add(new BigDecimal("4.547935")));
        assertThat(svc.spot(a, key("m1-ultramem-40")).priced()).isTrue();
        // M2 adds the upgrade premium to the memory-optimized core and RAM prices.
        BigDecimal m2core = new BigDecimal("0.03475").add(new BigDecimal("0.010425"));
        BigDecimal m2ram = new BigDecimal("0.004655").add(new BigDecimal("0.001396"));
        assertThat(svc.onDemand(a, key("m2-ultramem-208")).ratePerHour()).isEqualByComparingTo(
                m2core.multiply(BigDecimal.valueOf(208)).add(m2ram.multiply(BigDecimal.valueOf(5888))));
        assertThat(svc.spot(a, key("m2-ultramem-208")).priced()).isTrue();
        assertThat(c2od.source()).isEqualTo("gcp-billing-catalog");
        assertThat(c2od.sourceVersion()).isEqualTo("gcp-sku-match-v1");
        assertThat(c2od.currency().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void e2SmallIsPricedAtHalfAVcpuNotGuestCpus() {
        FakeGcp gcp = FakeGcp.healthy();
        machine(gcp, "e2-small", 2, 2048, true);
        PricedRate od = service(gcp, Clock.systemUTC()).onDemand(account(), key("e2-small"));
        // 0.5 vCPU x 0.02181159 + 2 GiB x 0.00292344, never 2 vCPU.
        assertThat(od.ratePerHour()).isEqualByComparingTo(new BigDecimal("0.5").multiply(new BigDecimal("0.02181159"))
                .add(new BigDecimal("2").multiply(new BigDecimal("0.00292344"))));
    }

    @Test
    void aSharedCoreTypeMissingFromTheTableIsNotPriced() {
        FakeGcp gcp = FakeGcp.healthy();
        machine(gcp, "e2-future-shared", 2, 1024, true);
        assertThat(service(gcp, Clock.systemUTC()).onDemand(account(), key("e2-future-shared")).status())
                .isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(GcpSharedCoreTable.v1().entries())
                .containsOnlyKeys("e2-micro", "e2-small", "e2-medium", "f1-micro", "g1-small");
        assertThat(GcpSharedCoreTable.v1().billedVcpu("e2-micro")).contains(new BigDecimal("0.25"));
        assertThat(GcpSharedCoreTable.v1().version()).isEqualTo("gcp-shared-core-v1");
    }

    @Test
    void customTypesAreParsedFromTheirNameWithoutAMachineTypeRead() {
        FakeGcp gcp = FakeGcp.healthy();
        // The fixture carries no custom SKU rows: not priced, never a guess.
        PricedRate custom = service(gcp, Clock.systemUTC()).onDemand(account(), key("n2-custom-4-16384"));
        assertThat(custom.status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(gcp.count("machineTypes.get")).isZero();
        GcpMachineShapes.Shape s = new GcpMachineShapes().shape(null, ZONE, "n2-custom-8-32768");
        assertThat(s.billedVcpu()).isEqualByComparingTo("8");
        assertThat(s.memoryGiB()).isEqualByComparingTo("32");
        assertThat(s.series()).isEqualTo("n2");
        assertThat(GcpMachineShapes.seriesOf("custom-2-4096")).isEqualTo("n1");
    }

    @Test
    void anUnknownSeriesOrLicensedImageIsNotPriced() {
        FakeGcp gcp = FakeGcp.healthy();
        machine(gcp, "t2d-standard-4", 4, 16384, false);
        GcpEstimatePriceService svc = service(gcp, Clock.systemUTC());
        assertThat(svc.onDemand(account(), key("t2d-standard-4")).status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(svc.onDemand(account(), new PriceKey("us-central1", ZONE, "n2-standard-4", Platform.WINDOWS, "Shared"))
                .status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(svc.onDemand(account(), new PriceKey("us-central1", ZONE, "n2-standard-4", Platform.UNKNOWN, "Shared"))
                .status()).isEqualTo(PriceStatus.NOT_PRICED);
    }

    @Test
    void anUnreadableTypeIsNotPricedAndNeverDefaulted() {
        FakeGcp gcp = FakeGcp.healthy();
        PricedRate r = service(gcp, Clock.systemUTC()).onDemand(account(), key("n2-standard-99"));
        assertThat(r.status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(r.ratePerHour()).isNull();
    }

    @Test
    void aRegionWithNoMatchingSkuIsNotPricedNotUnavailable() {
        FakeGcp gcp = FakeGcp.healthy();
        PricedRate r = service(gcp, Clock.systemUTC()).onDemand(account(),
                new PriceKey("asia-south1", "asia-south1-a", "n2-standard-4", Platform.LINUX, "Shared"));
        assertThat(r.status()).isEqualTo(PriceStatus.NOT_PRICED);
        assertThat(r.ratePerHour()).isNull();
    }

    @Test
    void aFailingCatalogIsUnavailableBacksOffAndIsNeverCached() {
        FakeGcp gcp = FakeGcp.healthy().fail("skus.list", ErrorKind.TRANSIENT);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        GcpEstimatePriceService svc = service(gcp, clock);
        assertThat(svc.onDemand(account(), key("n2-standard-4")).status()).isEqualTo(PriceStatus.PRICING_UNAVAILABLE);
        assertThat(svc.spot(account(), key("n2-standard-4")).status()).isEqualTo(PriceStatus.PRICING_UNAVAILABLE);
        assertThat(gcp.count("skus.list")).as("the backoff holds off the second call").isEqualTo(1);
        // After the backoff the fault is gone: priced, and now cached.
        gcp.faults.clear();
        clock.advanceMinutes(6);
        assertThat(svc.onDemand(account(), key("n2-standard-4")).priced()).isTrue();
        svc.spot(account(), key("n2-standard-4"));
        assertThat(gcp.count("skus.list")).isEqualTo(2);
        clock.advanceMinutes(60 * 25);
        svc.onDemand(account(), key("n2-standard-4"));
        assertThat(gcp.count("skus.list")).as("the TTL is at most 24 h").isEqualTo(3);
    }

    @Test
    void everySeriesTheMatcherAcceptsHasAFixtureMatch() {
        List<JsonNode> skus = FakeGcp.arr(FakeGcp.fixture("listskus-compute.json").path("skus"));
        List<String> unmatched = new ArrayList<>();
        for (String series : GcpSkuMatcher.acceptedSeries()) {
            for (boolean spot : new boolean[] {false, true}) {
                if (GcpSkuMatcher.match(skus, series, "us-central1", false, spot).isEmpty()) {
                    unmatched.add(series + (spot ? "/spot" : "/on-demand"));
                }
            }
        }
        assertThat(unmatched).as("a new family needs a table row and a fixture refresh, never a fallback").isEmpty();
    }

    @Test
    void anAmbiguousMatchIsNotPicked() {
        List<JsonNode> skus = new ArrayList<>(FakeGcp.arr(FakeGcp.fixture("listskus-compute.json").path("skus")));
        ObjectNode dup = (ObjectNode) skus.stream()
                .filter(s -> s.path("description").asText().startsWith("N2 Instance Core running in Americas"))
                .findFirst().orElseThrow().deepCopy();
        ((ObjectNode) dup.path("pricingInfo").get(0).path("pricingExpression").path("tieredRates").get(0)
                .path("unitPrice")).put("nanos", 1);
        skus.add(dup);
        assertThat(GcpSkuMatcher.match(skus, "n2", "us-central1", false, false)).isEmpty();
    }

    @Test
    void unitPriceConversionIsExact() throws Exception {
        JsonNode unit = FakeGcp.JSON.readTree("{\"units\":\"1\",\"nanos\":5000000}");
        assertThat(GcpSkuMatcher.unitPrice(unit)).isEqualByComparingTo("1.005");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceMinutes(long m) {
            now = now.plusSeconds(m * 60);
        }

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
    }
}
