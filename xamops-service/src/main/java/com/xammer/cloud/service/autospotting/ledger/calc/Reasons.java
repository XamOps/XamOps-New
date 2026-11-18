package com.xammer.cloud.service.autospotting.ledger.calc;

/**
 * Reason and unknown-input codes the calculator writes (design-ledger.md §3.6, §5, §6.2, §6.3). One
 * string constant per code, so entries, fixtures and tests spell them the same way.
 */
public final class Reasons {
    private Reasons() {}

    // Exclusions (6.2)
    public static final String LEGACY_UNCONFIRMED = "LEGACY_UNCONFIRMED";
    public static final String OD_FLOOR = "OD_FLOOR";
    public static final String ORIGINAL_SPOT_SHARE = "ORIGINAL_SPOT_SHARE";
    public static final String OVER_DESIRED = "OVER_DESIRED";
    public static final String COMMITMENT_COVERABLE = "COMMITMENT_COVERABLE";
    public static final String BEFORE_GO_LIVE = "BEFORE_GO_LIVE";
    public static final String PROVIDER_NOT_CHARGED = "PROVIDER_NOT_CHARGED";
    public static final String INTERRUPTION_CAUSE_UNKNOWN = "INTERRUPTION_CAUSE_UNKNOWN";
    public static final String UNKNOWN_INTERVAL_END = "UNKNOWN_INTERVAL_END";
    /** {@code STATE_<STATE>} for a window whose state is not billable. */
    public static final String STATE_PREFIX = "STATE_";
    public static final String STATE_REVERTING = "STATE_REVERTING";

    // Commercial reasons (3.1)
    public static final String NO_CONTRACT = "NO_CONTRACT";
    public static final String BEFORE_CONTRACT = "BEFORE_CONTRACT";
    public static final String NO_FEE_ACCOUNT = "NO_FEE_ACCOUNT";

    // Tier caps (5.1, 5.2, 5.4)
    public static final String DISCOUNT_NOT_DECLARED = "DISCOUNT_NOT_DECLARED";
    public static final String COMMITMENT_DATA_UNAVAILABLE = "COMMITMENT_DATA_UNAVAILABLE";
    public static final String COMMITMENT_RATE_UNKNOWN = "COMMITMENT_RATE_UNKNOWN";
    /** The inputs are complete but reconciliation has not verified the day against the bill. */
    public static final String NOT_RECONCILED = "NOT_RECONCILED";

    // Unknown inputs (6.3)
    public static final String MISSING_SPOT_PRICE = "MISSING_SPOT_PRICE";
    public static final String MISSING_OD_PRICE = "MISSING_OD_PRICE";
    public static final String MISSING_PLATFORM = "MISSING_PLATFORM";
    public static final String MISSING_WEIGHT = "MISSING_WEIGHT";
    public static final String MISSING_DESIRED = "MISSING_DESIRED";
    public static final String MISSING_SNAPSHOT = "MISSING_SNAPSHOT";
    public static final String MISSING_STATE_ORDER = "MISSING_STATE_ORDER";
}
