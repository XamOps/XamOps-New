package com.xammer.cloud.service.gcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the currency contract and the failure behaviour of the GCP pricing service.
 *
 * <p>The service used to return INR, converted from USD with a hardcoded 83.0, while
 * {@code GcpOptimizationService} returned USD. The GCP dashboard added the two figures together,
 * so the autospotting share of its headline potential-savings number was inflated about 83-fold.
 * These tests pin the unit and pin the refusal to invent a price.
 *
 * <p>The catalog itself is not exercised — that would make the suite depend on live GCP
 * credentials. What is exercised is the path taken when the catalog cannot be reached, which is
 * the path this deployment is actually on.
 */
class GcpPricingCurrencyTest {

    private GcpPricingService serviceWithoutCatalog() {
        GcpClientProvider provider = Mockito.mock(GcpClientProvider.class);
        Mockito.when(provider.getCloudCatalogClient(Mockito.anyString())).thenReturn(Optional.empty());
        return new GcpPricingService(provider);
    }

    @Test
    @DisplayName("prices are quoted in USD")
    void pricesAreUsd() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("n2-standard-4", "asia-south1", "proj");

        assertThat(pricing.getCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("an n2-standard-4 estimate is a plausible USD hourly rate, not an INR one")
    void estimateIsInUsdMagnitude() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("n2-standard-4", "asia-south1", "proj");

        assertThat(pricing.isPriced()).isTrue();
        // 4 vCPU + 16 GiB is a few cents per hour in USD. Multiplied by 83 it would be several
        // rupees, which is what the old code returned from this same call.
        assertThat(pricing.getOnDemandPrice()).isBetween(0.05, 0.50);
    }

    @Test
    @DisplayName("an unreachable catalog is reported as an estimate, not as a list price")
    void unreachableCatalogIsLabelled() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("n2-standard-4", "asia-south1", "proj");

        assertThat(pricing.getSource()).isEqualTo(GcpPricingService.PriceSource.ESTIMATE_TABLE);
        assertThat(pricing.getNote()).contains("no regional adjustment");
    }

    @Test
    @DisplayName("an unparseable machine type is unpriced rather than guessed")
    void unparseableMachineTypeIsUnpriced() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("not-a-machine", "asia-south1", "proj");

        assertThat(pricing.isPriced()).isFalse();
        assertThat(pricing.getSource()).isEqualTo(GcpPricingService.PriceSource.UNAVAILABLE);
        assertThat(pricing.getOnDemandPrice()).isZero();
        assertThat(pricing.getSpotPrice()).isZero();
    }

    @Test
    @DisplayName("a missing region is unpriced rather than defaulted to US pricing")
    void missingRegionIsUnpriced() {
        // The old code applied a multiplier of 1.0 for anything it did not recognise, so a null
        // region silently produced US prices.
        var service = serviceWithoutCatalog();

        assertThat(service.getMachineTypePricing("n2-standard-4", null, "proj").isPriced()).isFalse();
        assertThat(service.getMachineTypePricing("n2-standard-4", "  ", "proj").isPriced()).isFalse();
    }

    @Test
    @DisplayName("a blank machine type is unpriced")
    void blankMachineTypeIsUnpriced() {
        var service = serviceWithoutCatalog();

        assertThat(service.getMachineTypePricing(null, "asia-south1", "proj").isPriced()).isFalse();
        assertThat(service.getMachineTypePricing("", "asia-south1", "proj").isPriced()).isFalse();
    }

    @Test
    @DisplayName("spot is cheaper than on-demand, and savings are non-negative")
    void spotIsCheaper() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("n2-standard-4", "asia-south1", "proj");

        assertThat(pricing.getSpotPrice()).isLessThan(pricing.getOnDemandPrice());
        assertThat(pricing.getSavings()).isPositive();
        assertThat(pricing.getSavingsPercentage()).isBetween(0.0, 100.0);
    }

    @Test
    @DisplayName("an unpriced result yields no savings rather than a windfall")
    void unpricedYieldsNoSavings() {
        var pricing = serviceWithoutCatalog().getMachineTypePricing("not-a-machine", "asia-south1", "proj");

        assertThat(pricing.getSavings()).isZero();
        assertThat(pricing.getSavingsPercentage()).isZero();
    }

    @Test
    @DisplayName("a shared-core e2-micro costs a fraction of a full vCPU machine")
    void sharedCoreIsCheap() {
        var service = serviceWithoutCatalog();
        var micro = service.getMachineTypePricing("e2-micro", "us-central1", "proj");
        var medium = service.getMachineTypePricing("e2-medium", "us-central1", "proj");

        // Recording e2-micro as 2 vCPUs made it cost more than an e2-medium, which is 1 vCPU.
        assertThat(micro.getOnDemandPrice()).isLessThan(medium.getOnDemandPrice());
    }

    @Test
    @DisplayName("no project id means no catalog, so the result is an estimate")
    void noProjectIdFallsBackToEstimate() {
        // The two-argument overload cannot reach the catalog: there are no credentials without a
        // project. This is why the service never called an API for its whole existence.
        GcpClientProvider provider = Mockito.mock(GcpClientProvider.class);
        var pricing = new GcpPricingService(provider)
                .getMachineTypePricing("n2-standard-4", "asia-south1");

        assertThat(pricing.getSource()).isEqualTo(GcpPricingService.PriceSource.ESTIMATE_TABLE);
        Mockito.verify(provider, Mockito.never()).getCloudCatalogClient(Mockito.anyString());
    }

    @Test
    @DisplayName("an unpriced result is not cached, so a transient failure does not stick")
    void unpricedIsNotCached() {
        var service = serviceWithoutCatalog();
        service.getMachineTypePricing("not-a-machine", "asia-south1", "proj");

        assertThat((int) service.getCacheStats().get("resolvedPrices")).isZero();
    }

    @Test
    @DisplayName("cache statistics disclose the currency and catalog state")
    void cacheStatsDiscloseState() {
        var service = serviceWithoutCatalog();
        service.getMachineTypePricing("n2-standard-4", "asia-south1", "proj");

        var stats = service.getCacheStats();
        assertThat(stats).containsEntry("currency", "USD");
        assertThat(stats).containsEntry("catalogLoaded", false);
        assertThat((int) stats.get("resolvedPrices")).isEqualTo(1);
    }

    @Test
    @DisplayName("uses a 730-hour month, matching the rest of the codebase")
    void hoursPerMonth() {
        assertThat(GcpPricingService.HOURS_PER_MONTH).isEqualTo(730.0);
    }
}
