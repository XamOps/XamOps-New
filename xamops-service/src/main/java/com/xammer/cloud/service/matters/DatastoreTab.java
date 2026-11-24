package com.xammer.cloud.service.matters;

/**
 * The tabs of the XamLens Datastores page, as the {@code tab} query parameter names them.
 *
 * <p>{@link #vendorCategory()} is the Matters {@code category} the tab scopes to; {@link #ALL} has
 * none, because the vendor answers an unrecognised or blank category with an empty result set rather
 * than an error, so "all" must omit the parameter.
 */
public enum DatastoreTab {

    ALL("all", null),
    CLOUD("cloud", "cloud"),
    SAAS("saas", "saas"),
    ENDPOINT("endpoint", "endpoint"),
    ON_PREM("on_prem", "on_prem");

    private final String slug;
    private final String vendorCategory;

    DatastoreTab(String slug, String vendorCategory) {
        this.slug = slug;
        this.vendorCategory = vendorCategory;
    }

    /** The value of the {@code tab} parameter, and the readable tab segment of a cache key. */
    public String slug() {
        return slug;
    }

    /** The vendor {@code category}, or null for {@link #ALL}. */
    public String vendorCategory() {
        return vendorCategory;
    }

    /**
     * Strict parse: exactly one of the five slugs, case-sensitive.
     *
     * @throws DspmBadRequestException naming {@code tab} for anything else, including null.
     */
    public static DatastoreTab fromSlug(String slug) {
        if (slug != null) {
            for (DatastoreTab tab : values()) {
                if (tab.slug.equals(slug)) {
                    return tab;
                }
            }
        }
        throw new DspmBadRequestException("tab", "tab must be one of all, cloud, saas, endpoint, on_prem");
    }
}
