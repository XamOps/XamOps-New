package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.CloudAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

/**
 * The per-provider pricing interface for plan estimates, the list's "Est. monthly savings" and
 * dashboard potential (design-core.md §9.5). It is separate from the ledger's {@code price-snapshot}
 * path and its numbers never enter the ledger (FR-PRC-06).
 *
 * <p>Rules every implementation keeps (§9.5):
 * <ul>
 *   <li><strong>Unknown is never a number.</strong> A failed lookup, an unmatched SKU, or an unknown
 *       type/platform returns a non-PRICED status; the UI shows "Not priced" (AC-PLN-09). A catalog
 *       miss ({@link PriceStatus#NOT_PRICED}) is distinct from an unreachable catalog
 *       ({@link PriceStatus#PRICING_UNAVAILABLE}) (FR-PRC-04).</li>
 *   <li><strong>Only successes are cached.</strong> A failure is never cached as a value and records
 *       a per-account backoff (§9.5).</li>
 *   <li><strong>Provenance.</strong> Every rate carries its source, version and {@code asOf}.</li>
 * </ul>
 */
public interface EstimatePriceService {

    /** The On-Demand list rate for {@code key} (public prices, provider's own credentials). */
    PricedRate onDemand(CloudAccount account, PriceKey key);

    /** The Spot rate for {@code key} (the customer role's read session; AZ names are account-specific). */
    PricedRate spot(CloudAccount account, PriceKey key);

    /** Which provider this pricing service serves. */
    com.xammer.cloud.domain.autospotting.Provider provider();

    /** Whether a rate is priced, a catalog miss, or the catalog was unreachable (FR-PRC-04). */
    enum PriceStatus {
        PRICED,
        NOT_PRICED,
        PRICING_UNAVAILABLE
    }

    /** The operating-system/licence platform a price is for (§9.5, aws §7.5 platform map). */
    enum Platform {
        LINUX,
        RHEL,
        SUSE,
        WINDOWS,
        WINDOWS_SQL_STANDARD,
        WINDOWS_SQL_WEB,
        WINDOWS_SQL_ENTERPRISE,
        /** The AMI's {@code PlatformDetails} did not map to a priced platform: "Not priced". */
        UNKNOWN
    }

    /**
     * A price lookup key (§9.5). {@code zoneOrNull} is the Spot AZ for a Spot lookup and null for an
     * On-Demand list price; {@code tenancy} is {@code Shared} or {@code Dedicated}.
     */
    record PriceKey(String region, String zoneOrNull, String typeOrMachineType, Platform platform, String tenancy) {
        public PriceKey {
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException("region is required");
            }
            if (typeOrMachineType == null || typeOrMachineType.isBlank()) {
                throw new IllegalArgumentException("type is required");
            }
            if (platform == null) {
                platform = Platform.UNKNOWN;
            }
            if (tenancy == null || tenancy.isBlank()) {
                tenancy = "Shared";
            }
        }
    }

    /**
     * A priced (or not-priced) rate (§9.5). {@code ratePerHour} is null unless {@code status} is
     * {@link PriceStatus#PRICED}. {@code source}/{@code sourceVersion}/{@code asOf} are the provenance
     * the plan shows as {@code priceSources[{source, asOf}]}.
     */
    record PricedRate(PriceStatus status, BigDecimal ratePerHour, Currency currency,
                      String source, String sourceVersion, Instant asOf) {

        public boolean priced() {
            return status == PriceStatus.PRICED && ratePerHour != null;
        }

        /** A catalog miss: the SKU/type/platform is unknown (FR-PRC-04). Never cached as a value. */
        public static PricedRate notPriced(String source) {
            return new PricedRate(PriceStatus.NOT_PRICED, null, null, source, null, null);
        }

        /** The catalog was unreachable (a fault); records a backoff, never cached (FR-PRC-04). */
        public static PricedRate unavailable(String source) {
            return new PricedRate(PriceStatus.PRICING_UNAVAILABLE, null, null, source, null, null);
        }

        public static PricedRate priced(BigDecimal ratePerHour, Currency currency, String source,
                                        String sourceVersion, Instant asOf) {
            return new PricedRate(PriceStatus.PRICED, ratePerHour, currency, source, sourceVersion, asOf);
        }
    }
}
