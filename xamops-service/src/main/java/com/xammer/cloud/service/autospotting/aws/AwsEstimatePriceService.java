package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.EstimatePriceService;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS estimate pricing (design-aws.md §7.5, design-core.md §9.5). Plan estimates, the list KPI and
 * dashboard potential price through this; it never feeds the ledger (FR-PRC-06).
 *
 * <ul>
 *   <li><strong>On-Demand</strong>: Price List {@code GetProducts} on {@code AmazonEC2}, matched on
 *       region code, type, operating system, pre-installed software, licence model, tenancy and
 *       {@code capacitystatus=Used}. Exactly one product, else {@code NOT_PRICED}. XamOps's own
 *       credentials (list prices are public). Cached at most 24 h.</li>
 *   <li><strong>Spot</strong>: {@code DescribeSpotPriceHistory} through the customer role's read session
 *       (AZ names are account-specific), latest price per pool. Cached at most 1 h.</li>
 *   <li><strong>Only successes are cached.</strong> A failed or empty response is never a value and
 *       never $0. A failure records a per-account backoff (1 min doubling to 30 min); lookups inside
 *       the backoff report {@code PRICING_UNAVAILABLE} without calling the API, and the first lookup
 *       after it calls the API again.</li>
 *   <li><strong>Provenance</strong>: every rate carries source, version and {@code asOf}.</li>
 * </ul>
 */
@Component
public class AwsEstimatePriceService implements EstimatePriceService {

    static final Duration OD_TTL = Duration.ofHours(24);
    static final Duration SPOT_TTL = Duration.ofHours(1);
    static final Duration BACKOFF_START = Duration.ofMinutes(1);
    static final Duration BACKOFF_CAP = Duration.ofMinutes(30);
    static final String SOURCE_OD = "aws-price-list";
    static final String SOURCE_SPOT = "ec2-spot-price-history";
    private static final Currency USD = Currency.getInstance("USD");
    private static final int MAX_SPOT_PAGES = 10;

    private final PricingClient pricing;
    private final AwsViewSource views;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Cache<String, PricedRate> odCache = Caffeine.newBuilder()
            .maximumSize(20_000).expireAfterWrite(OD_TTL).build();
    private final Cache<String, PricedRate> spotCache = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterWrite(SPOT_TTL).build();
    private final Map<String, Backoff> backoffs = new ConcurrentHashMap<>();

    private record Backoff(Instant until, Duration delay) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AwsEstimatePriceService(PricingClient pricing, AwsViewSource views) {
        this(pricing, views, Clock.systemUTC());
    }

    AwsEstimatePriceService(PricingClient pricing, AwsViewSource views, Clock clock) {
        this.pricing = pricing;
        this.views = views;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.AWS;
    }

    // ---- platform map (§7.5): versioned, anything unmapped is "Not priced" ----

    /** The platform for an AMI's {@code PlatformDetails}; unmapped is {@link Platform#UNKNOWN}. */
    public static Platform platformOf(String platformDetails) {
        if (platformDetails == null) {
            return Platform.UNKNOWN;
        }
        return switch (platformDetails.trim()) {
            case "Linux/UNIX" -> Platform.LINUX;
            case "Red Hat Enterprise Linux" -> Platform.RHEL;
            case "SUSE Linux" -> Platform.SUSE;
            case "Windows" -> Platform.WINDOWS;
            case "Windows with SQL Server Standard" -> Platform.WINDOWS_SQL_STANDARD;
            case "Windows with SQL Server Web" -> Platform.WINDOWS_SQL_WEB;
            case "Windows with SQL Server Enterprise" -> Platform.WINDOWS_SQL_ENTERPRISE;
            default -> Platform.UNKNOWN;
        };
    }

    /** {@code [operatingSystem, preInstalledSw, licenseModel]} for a platform, or null when unmapped. */
    static String[] priceListAttributes(Platform p) {
        return switch (p) {
            case LINUX -> new String[] {"Linux", "NA", "No License required"};
            case RHEL -> new String[] {"RHEL", "NA", "No License required"};
            case SUSE -> new String[] {"SUSE", "NA", "No License required"};
            case WINDOWS -> new String[] {"Windows", "NA", "License Included"};
            case WINDOWS_SQL_STANDARD -> new String[] {"Windows", "SQL Std", "License Included"};
            case WINDOWS_SQL_WEB -> new String[] {"Windows", "SQL Web", "License Included"};
            case WINDOWS_SQL_ENTERPRISE -> new String[] {"Windows", "SQL Ent", "License Included"};
            case UNKNOWN -> null;
        };
    }

    /** The {@code ProductDescription} Spot history uses; SQL editions have none (Not priced). */
    static String spotProductDescription(Platform p) {
        return switch (p) {
            case LINUX -> "Linux/UNIX";
            case RHEL -> "Red Hat Enterprise Linux";
            case SUSE -> "SUSE Linux";
            case WINDOWS -> "Windows";
            default -> null;
        };
    }

    // ---- On-Demand ----

    @Override
    public PricedRate onDemand(CloudAccount account, PriceKey key) {
        String[] attrs = priceListAttributes(key.platform());
        if (attrs == null) {
            return PricedRate.notPriced(SOURCE_OD);
        }
        String cacheKey = key.region() + "|" + key.typeOrMachineType() + "|" + key.platform() + "|" + key.tenancy();
        PricedRate cached = odCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        String backoffKey = account.getAwsAccountId() + "|PRICE_LIST";
        if (inBackoff(backoffKey)) {
            return PricedRate.unavailable(SOURCE_OD);
        }
        try {
            List<String> products = new ArrayList<>();
            String token = null;
            do {
                final String next = token;
                GetProductsResponse resp = pricing.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonEC2")
                        .filters(term("regionCode", key.region()), term("instanceType", key.typeOrMachineType()),
                                term("operatingSystem", attrs[0]), term("preInstalledSw", attrs[1]),
                                term("licenseModel", attrs[2]), term("tenancy", key.tenancy()),
                                term("capacitystatus", "Used"))
                        .nextToken(next).build());
                products.addAll(resp.priceList());
                token = resp.nextToken();
            } while (token != null);
            clearBackoff(backoffKey);
            if (products.size() != 1) {
                return PricedRate.notPriced(SOURCE_OD); // exactly one product, else Not priced (not cached)
            }
            PricedRate rate = parseOnDemand(products.get(0));
            if (rate.priced()) {
                odCache.put(cacheKey, rate);
            }
            return rate;
        } catch (RuntimeException e) {
            recordFailure(backoffKey);
            return PricedRate.unavailable(SOURCE_OD);
        }
    }

    private PricedRate parseOnDemand(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String version = root.path("version").asText(null);
            String published = root.path("publicationDate").asText(null);
            for (JsonNode term : root.path("terms").path("OnDemand")) {
                for (JsonNode dim : term.path("priceDimensions")) {
                    String usd = dim.path("pricePerUnit").path("USD").asText(null);
                    if (usd != null && !usd.isBlank()) {
                        BigDecimal rate = new BigDecimal(usd);
                        if (rate.signum() > 0) {
                            Instant asOf = published == null ? clock.instant() : Instant.parse(published);
                            return PricedRate.priced(rate, USD, SOURCE_OD, version, asOf);
                        }
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return PricedRate.notPriced(SOURCE_OD);
        }
        return PricedRate.notPriced(SOURCE_OD);
    }

    private static Filter term(String field, String value) {
        return Filter.builder().type(FilterType.TERM_MATCH).field(field).value(value).build();
    }

    // ---- Spot ----

    @Override
    public PricedRate spot(CloudAccount account, PriceKey key) {
        String product = spotProductDescription(key.platform());
        if (product == null || key.zoneOrNull() == null) {
            return PricedRate.notPriced(SOURCE_SPOT);
        }
        SpotResult res = spotRates(account, key.region(), List.of(key.zoneOrNull()),
                List.of(key.typeOrMachineType()), key.platform());
        PricedRate r = res.rates().get(poolKey(key.zoneOrNull(), key.typeOrMachineType()));
        if (r != null) {
            return r;
        }
        return res.unavailable() ? PricedRate.unavailable(SOURCE_SPOT) : PricedRate.notPriced(SOURCE_SPOT);
    }

    /**
     * The median Spot rate per type across {@code zones}, for ranking and estimates. A type with no
     * priced pool in any zone is absent from the result. Throws nothing: a failure yields an empty map
     * and a backoff; callers distinguish via {@link #spot}.
     */
    public Map<String, BigDecimal> medianSpotByType(CloudAccount account, String region, List<String> zones,
                                                    List<String> types, Platform platform) {
        Map<String, PricedRate> rates = spotRates(account, region, zones, types, platform).rates();
        Map<String, BigDecimal> out = new HashMap<>();
        for (String type : types) {
            List<BigDecimal> prices = new ArrayList<>();
            for (String zone : zones) {
                PricedRate r = rates.get(poolKey(zone, type));
                if (r != null && r.priced()) {
                    prices.add(r.ratePerHour());
                }
            }
            if (!prices.isEmpty()) {
                prices.sort(BigDecimal::compareTo);
                int n = prices.size();
                BigDecimal median = n % 2 == 1 ? prices.get(n / 2)
                        : prices.get(n / 2 - 1).add(prices.get(n / 2)).divide(BigDecimal.valueOf(2), 6,
                                java.math.RoundingMode.HALF_UP);
                out.put(type, median);
            }
        }
        return out;
    }

    private record SpotResult(Map<String, PricedRate> rates, boolean unavailable) {
    }

    private SpotResult spotRates(CloudAccount account, String region, List<String> zones,
                                 List<String> types, Platform platform) {
        String product = spotProductDescription(platform);
        Map<String, PricedRate> out = new HashMap<>();
        if (product == null) {
            return new SpotResult(out, false);
        }
        List<String> missingTypes = new ArrayList<>();
        for (String type : types) {
            boolean allCached = true;
            for (String zone : zones) {
                PricedRate c = spotCache.getIfPresent(spotCacheKey(account, region, zone, type, product));
                if (c != null) {
                    out.put(poolKey(zone, type), c);
                } else {
                    allCached = false;
                }
            }
            if (!allCached) {
                missingTypes.add(type);
            }
        }
        if (missingTypes.isEmpty()) {
            return new SpotResult(out, false);
        }
        String backoffKey = account.getAwsAccountId() + "|SPOT_PRICE_HISTORY";
        if (inBackoff(backoffKey)) {
            return new SpotResult(out, true);
        }
        boolean unavailable = false;
        try {
            AwsReadOnlyView view = views.view(account, region);
            Instant now = clock.instant();
            Set<String> zoneSet = Set.copyOf(zones);
            String token = null;
            int pages = 0;
            do {
                final String next = token;
                DescribeSpotPriceHistoryResponse resp = view.ec2().describeSpotPriceHistory(
                        DescribeSpotPriceHistoryRequest.builder()
                                .instanceTypesWithStrings(missingTypes)
                                .productDescriptions(product)
                                .startTime(now)
                                .nextToken(next).build());
                for (SpotPrice sp : resp.spotPriceHistory()) {
                    if (!zoneSet.contains(sp.availabilityZone()) || sp.spotPrice() == null) {
                        continue;
                    }
                    String pool = poolKey(sp.availabilityZone(), sp.instanceTypeAsString());
                    if (out.containsKey(pool)) {
                        continue; // the first entry for a pool is its latest
                    }
                    BigDecimal price = new BigDecimal(sp.spotPrice());
                    if (price.signum() <= 0) {
                        continue; // never a $0 value
                    }
                    PricedRate rate = PricedRate.priced(price, USD, SOURCE_SPOT, null,
                            sp.timestamp() == null ? now : sp.timestamp());
                    out.put(pool, rate);
                    spotCache.put(spotCacheKey(account, region, sp.availabilityZone(), sp.instanceTypeAsString(),
                            product), rate);
                }
                token = resp.nextToken();
                pages++;
            } while (token != null && !token.isEmpty() && pages < MAX_SPOT_PAGES);
            clearBackoff(backoffKey);
        } catch (RuntimeException e) {
            recordFailure(backoffKey);
            unavailable = true;
        }
        return new SpotResult(out, unavailable);
    }

    private static String poolKey(String zone, String type) {
        return zone + "|" + type;
    }

    private static String spotCacheKey(CloudAccount account, String region, String zone, String type,
                                       String product) {
        return account.getAwsAccountId() + "|" + region + "|" + zone + "|" + type + "|" + product;
    }

    // ---- backoff ----

    private boolean inBackoff(String key) {
        Backoff b = backoffs.get(key);
        return b != null && clock.instant().isBefore(b.until());
    }

    private void clearBackoff(String key) {
        backoffs.remove(key);
    }

    private void recordFailure(String key) {
        backoffs.merge(key, new Backoff(clock.instant().plus(BACKOFF_START), BACKOFF_START), (old, fresh) -> {
            Duration next = old.delay().multipliedBy(2);
            if (next.compareTo(BACKOFF_CAP) > 0) {
                next = BACKOFF_CAP;
            }
            return new Backoff(clock.instant().plus(next), next);
        });
    }
}
