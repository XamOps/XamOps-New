package com.xammer.billops.service.cdn;

import java.util.Map;

/**
 * Decodes CloudFront usage types into the two things private-rate billing needs:
 * which geography the traffic was served from, and what kind of usage it is.
 *
 * WHY THE USAGE TYPE AND NOT THE REGION COLUMN
 * CloudFront is a global service, so CUR's product_region_code is normally blank on
 * its line items. The billing geography is encoded in the usage-type prefix instead
 * ("IN-DataTransfer-Out-Bytes" is traffic served from Indian edge locations). Since
 * private per-GB rates differ several-fold between geographies — India and South
 * America are far more expensive to serve than the US or Europe — reading the prefix
 * is the only way to price a line correctly.
 *
 * WHY THE USAGE CLASS MATTERS
 * The same service emits data transfer measured in GB, requests measured per 10,000,
 * and invalidations measured per path. A per-GB rate applied to a requests row would
 * produce a wildly wrong invoice, so each row is classified and the pricing step
 * refuses to mix them.
 */
public final class CloudFrontUsageParser {

    private CloudFrontUsageParser() {
    }

    /** Used when a usage type carries no geography prefix, e.g. "Invalidations". */
    public static final String GLOBAL = "GLOBAL";

    /**
     * CloudFront billing geographies, keyed by the prefix AWS puts on the usage type.
     *
     * These are CloudFront's own geography codes, not AWS region codes — CloudFront
     * bills by where the viewer was served from, which is a coarser grouping than
     * regions. Codes absent from this map are preserved verbatim rather than guessed
     * at, so a new geography shows up as itself and can be priced, instead of being
     * silently folded into the wrong bucket.
     */
    private static final Map<String, String> GEOGRAPHY_NAMES = Map.ofEntries(
            Map.entry("US", "United States"),
            Map.entry("CA", "Canada"),
            Map.entry("EU", "Europe"),
            Map.entry("IN", "India"),
            Map.entry("JP", "Japan"),
            Map.entry("AP", "Asia Pacific"),
            Map.entry("AU", "Australia & New Zealand"),
            Map.entry("SA", "South America"),
            Map.entry("ME", "Middle East"),
            Map.entry("AF", "Africa"),
            Map.entry("ZA", "South Africa"),
            Map.entry("IL", "Israel"),
            Map.entry(GLOBAL, "Global"));

    /**
     * The kind of usage a CloudFront line item represents.
     *
     * Deliberately coarse. The point is not to model every CloudFront charge but to
     * separate what is billed per GB from what is not, so a rate can never be applied
     * to the wrong unit.
     */
    public enum UsageClass {
        /** Data transferred out to viewers, billed per GB. */
        DATA_TRANSFER_OUT,
        /** HTTP/HTTPS requests, billed per 10,000 requests. */
        REQUESTS,
        /** Cache invalidation paths. */
        INVALIDATION,
        /**
         * Everything else CloudFront can charge for — field-level encryption,
         * real-time logs, functions, origin shield. Not priced from private rates
         * unless a rate is explicitly configured for it.
         */
        OTHER
    }

    /**
     * Extracts the geography code from a usage type.
     *
     * Returns {@link #GLOBAL} when there is no prefix, which is correct for
     * region-independent charges such as invalidations.
     */
    public static String geographyCode(String usageType) {
        if (usageType == null || usageType.isBlank()) {
            return GLOBAL;
        }
        String s = usageType.trim();
        int dash = s.indexOf('-');
        if (dash <= 0) {
            // No prefix at all, e.g. "Invalidations".
            return GLOBAL;
        }
        String prefix = s.substring(0, dash).toUpperCase();
        if (prefix.isEmpty()) {
            return GLOBAL;
        }

        // CloudFront viewer-geography codes are exactly two letters: AWS replaces the
        // region in a CloudFront usage type with a two-letter abbreviation ("IN", "AP",
        // "EU"). A longer prefix carrying digits is an AWS *region* code -- "APS1" is
        // ap-south-1 -- which scopes region-specific CloudFront charges such as origin
        // shield rather than naming a billing geography.
        //
        // Such a prefix is returned unchanged rather than reduced to its letters. An
        // earlier version stripped the trailing digits, which turned "APS1" into "APS":
        // not a real CloudFront geography, but close enough to "AP" that it matched the
        // Asia Pacific rate and quietly billed region-scoped usage at a viewer-egress
        // rate. Leaving it intact keeps it out of geography pricing, where it surfaces
        // as an unpriced line for review instead.
        return prefix;
    }

    /** Human-readable geography, falling back to the raw code when unrecognised. */
    public static String geographyName(String code) {
        if (code == null || code.isBlank()) {
            return GEOGRAPHY_NAMES.get(GLOBAL);
        }
        return GEOGRAPHY_NAMES.getOrDefault(code.toUpperCase(), code.toUpperCase());
    }

    /** True when this geography code is one we have a known name for. */
    public static boolean isKnownGeography(String code) {
        return code != null && GEOGRAPHY_NAMES.containsKey(code.toUpperCase());
    }

    /**
     * Classifies a usage type.
     *
     * Matching is on substrings rather than exact names because AWS adds usage types
     * over time; an unrecognised one lands in OTHER, where it is passed through at
     * AWS cost rather than being priced with a rate that may not apply to it.
     */
    public static UsageClass usageClass(String usageType) {
        if (usageType == null || usageType.isBlank()) {
            return UsageClass.OTHER;
        }
        String s = usageType.toLowerCase();

        // Checked before data transfer: "Invalidations" contains neither, but keeping
        // this first documents the precedence intent if AWS ever renames it.
        if (s.contains("invalidation")) {
            return UsageClass.INVALIDATION;
        }
        if (s.contains("request")) {
            return UsageClass.REQUESTS;
        }
        // "DataTransfer-Out-Bytes" is the billable egress. Transfer OUT to the origin
        // ("DataTransfer-Out-OBytes") is a different charge and is deliberately not
        // treated as viewer egress.
        if (s.contains("datatransfer-out-obytes")) {
            return UsageClass.OTHER;
        }
        if (s.contains("datatransfer-out")) {
            return UsageClass.DATA_TRANSFER_OUT;
        }
        return UsageClass.OTHER;
    }

    /**
     * The unit a class is expected to be measured in, used to reject a rate whose unit
     * does not match the usage it is being applied to.
     */
    public static String expectedUnit(UsageClass usageClass) {
        switch (usageClass) {
            case DATA_TRANSFER_OUT:
                return "GB";
            case REQUESTS:
                return "Requests";
            case INVALIDATION:
                return "Paths";
            default:
                return "";
        }
    }
}
