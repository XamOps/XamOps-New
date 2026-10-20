package com.xammer.cloud.service.automation.workload.cost;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.AwsPricingService;
import com.xammer.cloud.service.SpotPricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Resolves one node's {@code (account, instanceType, capacityType, zone, region)} to a
 * {@link NodeRate}, cached with TTL, reusing the existing AWS pricing paths.
 *
 * <p>On-demand reuses {@link AwsPricingService#getHourlyCostQuote} and accepts <strong>only</strong>
 * a {@code PriceSource.PRICE_LIST} (authoritative) quote — any static/estimate/unavailable figure is
 * treated as {@link NodeRate.Reason#NO_LIVE_PRICE}. Spot issues its own AZ-filtered
 * {@code DescribeSpotPriceHistory} (mirroring {@link SpotPricingService}'s window + max-by-timestamp,
 * adding {@code .availabilityZone(zone)}); a spot node with an absent/unparseable zone is
 * {@link NodeRate.Reason#NODE_ZONE_UNKNOWN} and <strong>no request is built</strong>.
 *
 * <p>The two caches are keyed so no tenant can read another tenant's spot rate: the on-demand key is
 * {@code instanceType|region} (list price is account-independent), the spot key is
 * {@code awsAccountId|region|instanceType|zone} (AZ names are per-account). Structural verdicts are
 * held the full success TTL; transient verdicts expire after the short {@code transientNegativeTtl}
 * floor so an AWS blip is re-attempted on the next poll. A {@code maxCacheEntries} guard is logged if
 * exceeded. Any failure returns an unpriceable {@link NodeRate}, never a zero.
 */
@Service
public class NodeRateResolver {

    private static final Logger logger = LoggerFactory.getLogger(NodeRateResolver.class);

    private static final String PRODUCT_DESCRIPTION = "Linux/UNIX";
    private static final int SPOT_MAX_RESULTS = 100;
    private static final String CAPACITY_SPOT = "spot";
    private static final String CAPACITY_ON_DEMAND = "on-demand";
    /** A region shape once the trailing AZ letter has been dropped, e.g. {@code ap-south-1}. */
    private static final Pattern REGION_SHAPE = Pattern.compile("^[a-z]{2}-[a-z]+-\\d$");

    private final AwsPricingService awsPricingService;
    private final SpotPricingService spotPricingService;
    private final AwsClientProvider awsClientProvider;
    private final K8sCostProperties properties;
    private final Clock clock;

    private final ConcurrentHashMap<String, CachedRate> onDemandCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedRate> spotCache = new ConcurrentHashMap<>();

    @Autowired
    public NodeRateResolver(AwsPricingService awsPricingService,
                            SpotPricingService spotPricingService,
                            AwsClientProvider awsClientProvider,
                            K8sCostProperties properties) {
        this(awsPricingService, spotPricingService, awsClientProvider, properties, Clock.systemUTC());
    }

    NodeRateResolver(AwsPricingService awsPricingService,
                     SpotPricingService spotPricingService,
                     AwsClientProvider awsClientProvider,
                     K8sCostProperties properties,
                     Clock clock) {
        this.awsPricingService = awsPricingService;
        this.spotPricingService = spotPricingService;
        this.awsClientProvider = awsClientProvider;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Resolve one node's hourly rate. {@code capacityType} may be {@code null} (no label) — then
     * on-demand is assumed and {@link NodeRate#capacityTypeInferred()} is set.
     */
    public NodeRate resolve(CloudAccount account, String instanceType, String capacityType,
                            String zone, String region) {
        if (!properties.enabled()) {
            return NodeRate.unpriceable(NodeRate.Reason.PRICING_DISABLED,
                    "cost pricing is disabled", instanceType, capacityType, zone, region);
        }
        if (instanceType == null || instanceType.isBlank()) {
            return NodeRate.unpriceable(NodeRate.Reason.UNKNOWN_INSTANCE_TYPE,
                    "node reports no EC2 instance type", instanceType, capacityType, zone, region);
        }
        String type = instanceType.trim();

        boolean inferred = capacityType == null || capacityType.isBlank();
        String effectiveCapacity = inferred ? CAPACITY_ON_DEMAND : capacityType.trim().toLowerCase();

        if (CAPACITY_SPOT.equals(effectiveCapacity)) {
            return resolveSpot(account, type, zone, region);
        }
        return resolveOnDemand(type, effectiveCapacity, zone, region, inferred);
    }

    private NodeRate resolveOnDemand(String type, String capacityType, String zone, String region,
                                     boolean inferred) {
        String key = type + "|" + region;
        CachedRate cached = onDemandCache.get(key);
        if (cached != null && !cached.isExpired(clock.instant())) {
            return cached.rate();
        }

        NodeRate rate;
        try {
            AwsPricingService.RateQuote quote = awsPricingService.getHourlyCostQuote(type, region);
            if (quote != null && quote.source() == AwsPricingService.PriceSource.PRICE_LIST) {
                rate = inferred
                        ? NodeRate.inferred(quote.usdPerHour(), NodeRate.Source.PRICE_LIST, quote.note(),
                                type, zone, region)
                        : NodeRate.priceable(quote.usdPerHour(), NodeRate.Source.PRICE_LIST, quote.note(),
                                type, capacityType, zone, region);
            } else {
                rate = NodeRate.unpriceable(NodeRate.Reason.NO_LIVE_PRICE,
                        "no live AWS price for " + type + " in " + region, type, capacityType, zone,
                        region);
            }
        } catch (RuntimeException e) {
            logger.warn("On-demand price lookup failed for {} in {}: {}", type, region, e.getMessage());
            rate = NodeRate.unpriceable(NodeRate.Reason.NO_LIVE_PRICE,
                    "no live AWS price for " + type + " in " + region, type, capacityType, zone, region);
        }
        store(onDemandCache, key, rate, properties.onDemandTtl());
        return rate;
    }

    private NodeRate resolveSpot(CloudAccount account, String type, String zone, String region) {
        String parsedZone = parseableZone(zone);
        if (parsedZone == null) {
            // No AZ to key the spot lookup on: never issue a blank-availability-zone request.
            return NodeRate.unpriceable(NodeRate.Reason.NODE_ZONE_UNKNOWN,
                    "spot node has no parseable availability zone", type, CAPACITY_SPOT, zone, region);
        }
        String accountId = account == null ? "unknown" : account.getAwsAccountId();
        String key = accountId + "|" + region + "|" + type + "|" + parsedZone;
        CachedRate cached = spotCache.get(key);
        if (cached != null && !cached.isExpired(clock.instant())) {
            return cached.rate();
        }

        NodeRate rate;
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            Instant now = clock.instant();
            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                    .instanceTypes(InstanceType.fromValue(type))
                    .availabilityZone(parsedZone)
                    .productDescriptions(PRODUCT_DESCRIPTION)
                    .startTime(now.minus(properties.spotLookback()))
                    .endTime(now)
                    .maxResults(SPOT_MAX_RESULTS)
                    .build();
            Optional<SpotPrice> latest = ec2.describeSpotPriceHistory(request).spotPriceHistory()
                    .stream().max(Comparator.comparing(SpotPrice::timestamp));
            if (latest.isPresent()) {
                double price = Double.parseDouble(latest.get().spotPrice());
                rate = NodeRate.priceable(price, NodeRate.Source.SPOT_HISTORY,
                        "AWS spot price for " + type + " in " + parsedZone, type, CAPACITY_SPOT,
                        parsedZone, region);
            } else {
                rate = NodeRate.unpriceable(NodeRate.Reason.NO_SPOT_HISTORY,
                        "no spot price history for " + type + " in " + parsedZone, type, CAPACITY_SPOT,
                        parsedZone, region);
            }
        } catch (RuntimeException e) {
            logger.warn("Spot price lookup failed for {} in {}: {}", type, parsedZone, e.getMessage());
            rate = NodeRate.unpriceable(NodeRate.Reason.NO_SPOT_HISTORY,
                    "no spot price history for " + type + " in " + parsedZone, type, CAPACITY_SPOT,
                    parsedZone, region);
        }
        store(spotCache, key, rate, properties.spotTtl());
        return rate;
    }

    /**
     * The node's availability zone when it is parseable, else {@code null}. A zone is parseable iff,
     * after dropping its final {@code [a-z]}, the remainder is a region shape {@code ^[a-z]{2}-[a-z]+-\d$}.
     */
    static String parseableZone(String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        String trimmed = zone.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last < 'a' || last > 'z') {
            return null;
        }
        String region = trimmed.substring(0, trimmed.length() - 1);
        return REGION_SHAPE.matcher(region).matches() ? trimmed : null;
    }

    private void store(ConcurrentHashMap<String, CachedRate> cache, String key, NodeRate rate,
                       java.time.Duration successTtl) {
        java.time.Duration ttl = successTtl;
        if (!rate.priceable() && !rate.isStructural()) {
            // Transient verdict (NO_LIVE_PRICE / NO_SPOT_HISTORY): recover after the short floor.
            ttl = properties.transientNegativeTtl();
        }
        cache.put(key, new CachedRate(rate, clock.instant().plus(ttl)));
        if (cache.size() > properties.maxCacheEntries()) {
            logger.warn("NodeRateResolver cache exceeded {} entries", properties.maxCacheEntries());
        }
    }

    private record CachedRate(NodeRate rate, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
