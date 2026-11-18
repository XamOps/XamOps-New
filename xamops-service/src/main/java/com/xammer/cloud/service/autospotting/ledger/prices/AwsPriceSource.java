package com.xammer.cloud.service.autospotting.ledger.prices;

import com.xammer.cloud.domain.CloudAccount;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where the price-snapshot job gets AWS prices (design-ledger.md §4.1). Every method either returns what
 * AWS stated or throws: a failed or partial fetch must surface as an exception so the job writes nothing
 * for it (AC-LED-10). Amounts are parsed with {@code new BigDecimal(String)}, never through a binary
 * floating-point type (§4.1 Parsing).
 */
public interface AwsPriceSource {

    /** One Spot price point: the price that holds from {@code timestamp} until the pool's next point. */
    record SpotQuote(String availabilityZone, String instanceType, String productDescription, BigDecimal price,
                     Instant timestamp) {
    }

    /** The On-Demand list price of one (region, type, platform) with the version it was published under. */
    record ListPrice(BigDecimal ratePerHour, Instant effectiveFrom, String sourceVersion, String rawRecord) {
    }

    /**
     * Spot price history for the given types since {@code since}, through the customer role's read session
     * (AZ names are account-specific). Throws on any failure.
     */
    List<SpotQuote> spotHistory(CloudAccount account, String region, List<String> instanceTypes,
                                String productDescription, Instant since);

    /** The list price, or empty when the Price List does not answer with exactly one priced product. Throws on failure. */
    Optional<ListPrice> listPrice(String region, String instanceType, String operatingSystem, String preInstalledSw,
                                  String licenseModel);
}
