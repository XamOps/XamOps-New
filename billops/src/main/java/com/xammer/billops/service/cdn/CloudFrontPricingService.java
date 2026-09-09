package com.xammer.billops.service.cdn;

import com.xammer.billops.domain.CloudFrontPrivateRate;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prices CloudFront usage at a client's private per-unit rates.
 *
 * BillOps resells CloudFront under a private pricing agreement, so the CDN invoice line
 * is {@code quantity x ourRate} rather than AWS's cost with a percentage off. That
 * inverts which CUR field matters: the GB figure generates the revenue, and AWS's cost
 * becomes a margin reference.
 *
 * RATES COME FROM THE EXISTING {@link CloudFrontPrivateRate} TABLE, which is already
 * managed through AdminCloudFrontController and keyed by client + regionMatcher +
 * usageTypeMatcher. Nothing new is introduced here.
 *
 * BRIDGING TWO VOCABULARIES
 * Those matchers were written for the PDF/Cost Explorer flow, where a CloudFront
 * invoice labels egress "Bandwidth" and regions by display name ("Asia Pacific
 * (Mumbai)"). CUR uses neither: it emits "IN-DataTransfer-Out-Bytes" with a blank
 * region column, because CloudFront is global and the geography lives in the usage-type
 * prefix. Rather than require every existing rate to be re-entered, matching here
 * resolves both vocabularies to a common (geography, usage class) pair — see
 * {@link #matcherClass} and {@link #regionMatches}.
 *
 * DESIGN PRIORITY: NEVER SILENTLY MISPRICE
 *  - No matching rate? Passed through at AWS cost and flagged. Billing zero would give
 *    the CDN away; borrowing another geography's rate would invent a price.
 *  - Rate unit inconsistent with the usage class? Refused, because a per-GB rate on a
 *    requests row is wrong by orders of magnitude and still looks plausible.
 *  - Rate quoted "per 10000 requests"? The divisor is honoured — see {@link #unitDivisor}.
 */
@Service
public class CloudFrontPricingService {

    private static final Logger logger = LoggerFactory.getLogger(CloudFrontPricingService.class);

    private static final String CLOUDFRONT_SERVICE = "Amazon CloudFront";

    /** Matches a "per N units" rate quote, e.g. "Per 10000 Requests". */
    private static final Pattern PER_N = Pattern.compile("per\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /** Region matchers that mean "any geography". */
    private static final Set<String> WILDCARD_REGIONS = Set.of("all", "*", "any", "");

    private final CloudFrontPrivateRateRepository rateRepository;

    public CloudFrontPricingService(CloudFrontPrivateRateRepository rateRepository) {
        this.rateRepository = rateRepository;
    }

    /**
     * Prices the CloudFront rows of a month's CUR usage for one client.
     *
     * @param usageRows all CUR usage rows for the period; non-CloudFront rows are ignored
     * @param clientId  client whose private rates apply
     */
    public CloudFrontBill price(List<CurAggregates.SvcUsageEntry> usageRows, Long clientId) {
        CloudFrontBill bill = new CloudFrontBill();
        if (usageRows == null || usageRows.isEmpty() || clientId == null) {
            return bill;
        }

        List<CloudFrontPrivateRate> rates;
        try {
            rates = rateRepository.findByClientId(clientId);
        } catch (Exception e) {
            logger.warn("[CDN] Could not load CloudFront private rates for client {}: {}", clientId, e.getMessage());
            rates = List.of();
        }

        // Collapse to one line per geography + usage class. CUR splits usage by day and
        // by finer usage-type variants than pricing distinguishes, so a month holds
        // hundreds of rows that all bill at the same rate; the invoice wants one line per
        // rate showing the total quantity it applied to.
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (CurAggregates.SvcUsageEntry row : usageRows) {
            if (row == null) continue;
            if (!CLOUDFRONT_SERVICE.equalsIgnoreCase(nullSafe(row.getService()))) continue;

            String usageType = nullSafe(row.getUsageType());
            String geo = CloudFrontUsageParser.geographyCode(usageType);
            CloudFrontUsageParser.UsageClass cls = CloudFrontUsageParser.usageClass(usageType);

            Bucket b = buckets.computeIfAbsent(geo + '\u0000' + cls.name(),
                    k -> new Bucket(geo, cls, nullSafe(row.getUnit())));
            b.quantity = b.quantity.add(BigDecimal.valueOf(row.getQuantity()));
            b.awsCost = b.awsCost.add(BigDecimal.valueOf(row.getCost()));
            if (b.unit.isBlank() && !nullSafe(row.getUnit()).isBlank()) {
                b.unit = nullSafe(row.getUnit());
            }
            b.usageTypes.add(usageType);
        }

        for (Bucket b : buckets.values()) {
            bill.getLines().add(priceBucket(b, rates));
        }

        bill.getLines().sort(Comparator.comparing(CloudFrontBillLine::getAmount).reversed());
        return bill;
    }

    private CloudFrontBillLine priceBucket(Bucket b, List<CloudFrontPrivateRate> rates) {
        CloudFrontBillLine line = new CloudFrontBillLine();
        line.setGeographyCode(b.geo);
        line.setGeographyName(CloudFrontUsageParser.geographyName(b.geo));
        line.setUsageClass(b.usageClass.name());
        line.setQuantity(b.quantity.setScale(4, RoundingMode.HALF_UP));
        line.setUnit(b.unit.isBlank() ? CloudFrontUsageParser.expectedUnit(b.usageClass) : b.unit);
        line.setAwsCost(b.awsCost.setScale(6, RoundingMode.HALF_UP));
        line.setUsageTypeCount(b.usageTypes.size());

        CloudFrontPrivateRate rate = findRate(rates, b);

        if (rate == null) {
            line.setPriced(false);
            line.setAmount(b.awsCost.setScale(2, RoundingMode.HALF_UP));
            line.setNote("No private rate configured for " + line.getGeographyName()
                    + " / " + b.usageClass.name() + " — passed through at AWS cost");
            logger.warn("[CDN] No private rate for geography={} class={} — passing {} {} through at AWS cost ${}",
                    b.geo, b.usageClass, line.getQuantity(), line.getUnit(), line.getAmount());
            return line;
        }

        line.setPriced(true);
        line.setRatePerUnit(rate.getPrivateRate());
        line.setRateUnit(rate.getUnit());
        line.setRateDescription(rate.getRegionMatcher() + " / " + rate.getUsageTypeMatcher());

        // A rate quoted per 10,000 requests must not be multiplied by the raw request
        // count — that would overcharge by 10,000x.
        BigDecimal divisor = unitDivisor(rate.getUnit());
        BigDecimal billableUnits = divisor.compareTo(BigDecimal.ONE) == 0
                ? b.quantity
                : b.quantity.divide(divisor, 10, RoundingMode.HALF_UP);

        line.setBillableUnits(billableUnits.setScale(4, RoundingMode.HALF_UP));
        line.setAmount(billableUnits.multiply(rate.getPrivateRate()).setScale(2, RoundingMode.HALF_UP));
        return line;
    }

    /**
     * Finds the private rate for a bucket.
     *
     * A rate applies when its usageTypeMatcher resolves to the same usage class as the
     * bucket, its regionMatcher resolves to the same geography (or is a wildcard), and
     * its unit is consistent with that class. The most specific match wins, so a
     * geography-specific rate beats a wildcard.
     */
    private CloudFrontPrivateRate findRate(List<CloudFrontPrivateRate> rates, Bucket b) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        List<CloudFrontPrivateRate> candidates = new ArrayList<>();

        for (CloudFrontPrivateRate r : rates) {
            if (r.getPrivateRate() == null) continue;

            CloudFrontUsageParser.UsageClass matcherClass = matcherClass(r.getUsageTypeMatcher());
            if (matcherClass != b.usageClass) continue;
            if (!regionMatches(r.getRegionMatcher(), b.geo)) continue;

            if (!unitIsConsistent(r.getUnit(), b.usageClass)) {
                logger.error("[CDN] Ignoring private rate id={} ({} / {}): unit '{}' is inconsistent with {} usage",
                        r.getId(), r.getRegionMatcher(), r.getUsageTypeMatcher(), r.getUnit(), b.usageClass);
                continue;
            }
            candidates.add(r);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Prefer a rate that named this geography explicitly over a wildcard.
        candidates.sort(Comparator.comparingInt(r -> isWildcardRegion(r.getRegionMatcher()) ? 1 : 0));
        return candidates.get(0);
    }

    /**
     * Resolves a usageTypeMatcher to a usage class.
     *
     * "Bandwidth" is treated as data transfer out because that is the wording AWS uses on
     * CloudFront invoices, and therefore what existing rates were written against — CUR
     * calls the same thing "DataTransfer-Out-Bytes". Without this alias, every rate
     * configured for the PDF flow would fail to match CUR data and silently fall back to
     * AWS cost.
     */
    static CloudFrontUsageParser.UsageClass matcherClass(String matcher) {
        if (matcher == null || matcher.isBlank()) {
            return CloudFrontUsageParser.UsageClass.OTHER;
        }
        String m = matcher.toLowerCase();
        if (m.contains("invalidation")) {
            return CloudFrontUsageParser.UsageClass.INVALIDATION;
        }
        if (m.contains("request")) {
            return CloudFrontUsageParser.UsageClass.REQUESTS;
        }
        if (m.contains("bandwidth") || m.contains("datatransfer")
                || m.contains("data transfer") || m.contains("transfer-out")) {
            return CloudFrontUsageParser.UsageClass.DATA_TRANSFER_OUT;
        }
        return CloudFrontUsageParser.UsageClass.OTHER;
    }

    /**
     * Whether a regionMatcher refers to a CUR geography.
     *
     * Accepts the geography code ("IN"), its display name ("India"), a wildcard, or a
     * legacy AWS region display name that sits inside the geography — "Asia Pacific
     * (Mumbai)" contains "Mumbai", which belongs to India. The last case is what lets
     * rates entered for the PDF flow keep working against CUR.
     */
    static boolean regionMatches(String regionMatcher, String geoCode) {
        if (isWildcardRegion(regionMatcher)) {
            return true;
        }
        String m = regionMatcher.trim().toLowerCase();
        String code = geoCode == null ? "" : geoCode.trim().toLowerCase();

        // An exact geography code is unambiguous.
        if (m.equals(code)) {
            return true;
        }

        // A legacy AWS region name is STRICTLY MORE SPECIFIC than a geography name, so
        // when the matcher names one it decides the geography outright.
        //
        // This has to come before any name comparison. "Asia Pacific (Mumbai)" is billed
        // under India, but it also literally contains "Asia Pacific", so a containment
        // check would match the AP geography too -- and since both candidates are
        // non-wildcard, which one won came down to row order. That silently billed Asia
        // Pacific traffic at India's rate.
        String legacyGeo = legacyGeoFor(m);
        if (legacyGeo != null) {
            return legacyGeo.equalsIgnoreCase(code);
        }

        // Beyond that, compare against the geography's display name -- but only for a
        // geography we actually recognise. An unknown code has no meaningful display
        // name, and comparing against the raw code lets an "AP" rate capture an "APS1"
        // bucket.
        if (!CloudFrontUsageParser.isKnownGeography(geoCode)) {
            return false;
        }
        String name = CloudFrontUsageParser.geographyName(geoCode).toLowerCase();

        // Only the matcher-contains-name direction is safe. The reverse lets a short
        // matcher match inside an unrelated name: "US" appears in "Australia & New
        // Zealand", which would price Australian egress at the US rate.
        return m.equals(name) || m.contains(name);
    }

    /**
     * AWS region display-name fragments to CloudFront geography codes.
     *
     * Only the fragments that actually appear on CloudFront invoices, taken from the
     * region patterns the existing PDF parsers already recognise.
     */
    private static final Map<String, String> LEGACY_REGION_TO_GEO = Map.ofEntries(
            Map.entry("mumbai", "IN"),
            Map.entry("n. virginia", "US"),
            Map.entry("oregon", "US"),
            Map.entry("us east", "US"),
            Map.entry("us west", "US"),
            Map.entry("ireland", "EU"),
            Map.entry("frankfurt", "EU"),
            Map.entry("london", "EU"),
            Map.entry("singapore", "AP"),
            Map.entry("hong kong", "AP"),
            Map.entry("seoul", "AP"),
            Map.entry("tokyo", "JP"),
            Map.entry("sydney", "AU"),
            Map.entry("sao paulo", "SA"),
            Map.entry("são paulo", "SA"),
            Map.entry("bahrain", "ME"),
            Map.entry("cape town", "AF"),
            Map.entry("canada", "CA"));

    /**
     * The CloudFront geography a matcher's legacy AWS region name refers to, or null when
     * it names none.
     *
     * Longest fragment first, so "us east" is preferred over a bare substring match and a
     * matcher naming two regions resolves to the more specific one.
     */
    private static String legacyGeoFor(String lowercaseMatcher) {
        return LEGACY_REGION_TO_GEO.entrySet().stream()
                .filter(e -> lowercaseMatcher.contains(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private static boolean isWildcardRegion(String regionMatcher) {
        return regionMatcher == null || WILDCARD_REGIONS.contains(regionMatcher.trim().toLowerCase());
    }

    /**
     * Whether a rate's unit can price this usage class.
     *
     * Permissive about wording ("GB", "Per GB" and "GB/month" all mean per-gigabyte) but
     * strict about kind: a gigabyte rate cannot price requests, and vice versa.
     */
    static boolean unitIsConsistent(String unit, CloudFrontUsageParser.UsageClass usageClass) {
        String u = unit == null ? "" : unit.toLowerCase();
        switch (usageClass) {
            case DATA_TRANSFER_OUT:
                // Reject anything that mentions requests; otherwise accept GB/TB wording,
                // and accept a blank unit as "assumed per GB" since that is the only
                // sensible reading for egress.
                if (u.contains("request")) return false;
                return u.isBlank() || u.contains("gb") || u.contains("gigabyte") || u.contains("tb");
            case REQUESTS:
                return u.contains("request");
            case INVALIDATION:
                return u.isBlank() || u.contains("path") || u.contains("invalidation");
            default:
                return true;
        }
    }

    /**
     * Divisor implied by a unit such as "Per 10000 Requests".
     *
     * CloudFront request pricing is conventionally quoted per 10,000 requests, so
     * multiplying a raw request count by that rate would overcharge by four orders of
     * magnitude. Returns 1 when the unit names no quantity.
     */
    static BigDecimal unitDivisor(String unit) {
        if (unit == null || unit.isBlank()) {
            return BigDecimal.ONE;
        }
        Matcher m = PER_N.matcher(unit);
        if (m.find()) {
            try {
                BigDecimal n = new BigDecimal(m.group(1).replace(",", ""));
                if (n.compareTo(BigDecimal.ZERO) > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // Falls through to 1 — a malformed unit must not silently scale the bill.
            }
        }
        return BigDecimal.ONE;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Mutable accumulator for one geography + usage class. */
    private static final class Bucket {
        final String geo;
        final CloudFrontUsageParser.UsageClass usageClass;
        String unit;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal awsCost = BigDecimal.ZERO;
        final Set<String> usageTypes = new LinkedHashSet<>();

        Bucket(String geo, CloudFrontUsageParser.UsageClass usageClass, String unit) {
            this.geo = geo;
            this.usageClass = usageClass;
            this.unit = unit == null ? "" : unit;
        }
    }
}
