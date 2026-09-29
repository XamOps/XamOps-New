package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key parsing and query normalisation — the pure logic that everything else depends on.
 *
 * Worth testing directly because both classes exist to compensate for upstream behaviour that fails
 * silently: a malformed key produces a 401 that looks like a revoked credential, and an invalid sort or
 * filter produces a 200 that looks like an empty estate.
 */
class MattersApiKeyTest {

    /** Shape only — not a real credential. */
    private static final String SAMPLE = "mts_prod_a01d4ac1463a0709_SgOZHuy40LX6cpzs4SqvlKR22nZmk";

    @Test
    @DisplayName("parses the four segments")
    void parsesSegments() {
        MattersApiKey key = MattersApiKey.parse(SAMPLE).orElseThrow();

        assertThat(key.env()).isEqualTo("prod");
        assertThat(key.keyId()).isEqualTo("a01d4ac1463a0709");
        assertThat(key.secret()).isEqualTo("SgOZHuy40LX6cpzs4SqvlKR22nZmk");
        assertThat(key.lastFour()).isEqualTo("nZmk");
    }

    @Test
    @DisplayName("a secret containing underscores survives intact")
    void secretMayContainUnderscores() {
        // split("_") without a limit would truncate the secret at its first underscore and then reject a
        // perfectly valid key — or worse, authenticate with a partial one.
        MattersApiKey key = MattersApiKey.parse("mts_prod_keyid_abc_def_ghi").orElseThrow();

        assertThat(key.keyId()).isEqualTo("keyid");
        assertThat(key.secret()).isEqualTo("abc_def_ghi");
    }

    @Test
    @DisplayName("rejects anything that is not a Matters key")
    void rejectsMalformed() {
        assertThat(MattersApiKey.parse(null)).isEmpty();
        assertThat(MattersApiKey.parse("")).isEmpty();
        assertThat(MattersApiKey.parse("   ")).isEmpty();
        assertThat(MattersApiKey.parse("not-a-key")).isEmpty();
        // Wrong prefix — this is what guards the EncryptedStringConverter plaintext passthrough.
        assertThat(MattersApiKey.parse("xyz_prod_id_secret")).isEmpty();
        // Too few segments.
        assertThat(MattersApiKey.parse("mts_prod_onlyid")).isEmpty();
        // Empty segments.
        assertThat(MattersApiKey.parse("mts__id_secret")).isEmpty();
        assertThat(MattersApiKey.parse("mts_prod__secret")).isEmpty();
        assertThat(MattersApiKey.parse("mts_prod_id_")).isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void trimsWhitespace() {
        // Keys are pasted by hand into an admin form, and a trailing newline is the most common artefact.
        assertThat(MattersApiKey.isWellFormed("  " + SAMPLE + "\n")).isTrue();
    }

    @Test
    @DisplayName("toString never reveals the secret")
    void toStringRedacts() {
        String rendered = MattersApiKey.parse(SAMPLE).orElseThrow().toString();

        // The generated record toString would print every component, so one log line interpolating a key
        // would leak a live credential.
        assertThat(rendered).doesNotContain("SgOZHuy40LX6cpzs4SqvlKR22nZmk");
        assertThat(rendered).contains("prod").contains("a01d4ac1463a0709").contains("****nZmk");
    }

    @Test
    @DisplayName("an environment mismatch against the base URL is detected, but only when unambiguous")
    void environmentMatching() {
        MattersApiKey prodKey = MattersApiKey.parse(SAMPLE).orElseThrow();

        // No environment token in the host: nothing can be asserted, so it must pass. Rejecting here
        // would block the normal production configuration.
        assertThat(prodKey.matchesBaseUrl("https://app.matters.ai")).isTrue();
        assertThat(prodKey.matchesBaseUrl("https://app.ap-south-1.matters.ai")).isTrue();
        assertThat(prodKey.matchesBaseUrl(null)).isTrue();
        assertThat(prodKey.matchesBaseUrl("")).isTrue();

        // An explicit contradiction is worth catching at save time, while the operator who pasted the key
        // is still looking at the form.
        assertThat(prodKey.matchesBaseUrl("https://app.staging.matters.ai")).isFalse();

        MattersApiKey stagingKey = MattersApiKey.parse("mts_staging_id_secret").orElseThrow();
        assertThat(stagingKey.matchesBaseUrl("https://app.staging.matters.ai")).isTrue();
        assertThat(stagingKey.matchesBaseUrl("https://app.prod.matters.ai")).isFalse();
    }

    // ── query normalisation ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the Cloud surface scopes by resource_type, never by a comma-joined category")
    void cloudScopesByResourceType() {
        Map<String, List<String>> params = DspmAssetQuery.cloud(null, null, null, null, null)
                .toFilterParams();

        // The regression this pins: `category` is a tab selector, not one of the six multi-value filters,
        // so `category=cloud,on_prem` is unrecognised -- and the API answers an unrecognised filter with
        // an EMPTY RESULT SET on a 200 rather than an error. It shipped and read as "this customer has no
        // datastores".
        //
        // resource_type=DATASTORE expresses the same intent correctly: cloud and on-prem datastores are
        // both DATASTORE, SaaS is SAAS_PRINCIPAL, endpoints are ENDPOINT_HOST. Measured live it returns
        // 68 -- exactly the vendor Cloud screen's count.
        assertThat(params.get("resource_type")).containsExactly("DATASTORE");
        assertThat(params).doesNotContainKey("category");
    }

    @Test
    @DisplayName("a blank scope is never sent, because it would silently return nothing")
    void blankScopeIsOmitted() {
        Map<String, List<String>> params =
                DspmAssetQuery.of(null, null, null, null, null, null, null).toFilterParams();

        // Sending category="" would be an unrecognised value, which means an empty result set rather than
        // an error. Omitting it lets the API apply its own default instead.
        assertThat(params).doesNotContainKey("category");
        assertThat(params).doesNotContainKey("resource_type");
    }

    @Test
    @DisplayName("a caller cannot override the resource_type scope with a filter")
    void resourceTypeIsNotAUserFilter() {
        Map<String, List<String>> params = DspmAssetQuery.cloud(null, null, null, null,
                Map.of("resource_type", List.of("SAAS_PRINCIPAL"))).toFilterParams();

        // Otherwise selecting SAAS_PRINCIPAL on the Cloud tab would empty the table, and the user would
        // have no way to tell that from "no data".
        assertThat(params.get("resource_type")).containsExactly("DATASTORE");
    }

    @Test
    @DisplayName("a single category is still valid, for the summary endpoint's tab argument")
    void singleCategoryPassesThrough() {
        Map<String, List<String>> params =
                DspmAssetQuery.of("saas", null, null, null, null, null, null).toFilterParams();

        assertThat(params.get("category")).containsExactly("saas");
        assertThat(params).doesNotContainKey("resource_type");
    }

    @Test
    @DisplayName("unknown sort fields fall back to the default instead of being silently ignored upstream")
    void sortValidation() {
        // Upstream answers 200 and applies its own default ordering for an unrecognised sort, so a typo
        // presents as "the sort control does nothing".
        assertThat(DspmAssetQuery.cloud(null, "nonsense", null, null, null)
                .toListParams().get("sort")).containsExactly(DspmAssetQuery.DEFAULT_SORT);

        assertThat(DspmAssetQuery.cloud(null, "-risk_score", null, null, null)
                .toListParams().get("sort")).containsExactly("-risk_score");
        assertThat(DspmAssetQuery.cloud(null, "name", null, null, null)
                .toListParams().get("sort")).containsExactly("name");
        assertThat(DspmAssetQuery.cloud(null, "-sensitive_record_count", null, null, null)
                .toListParams().get("sort")).containsExactly("-sensitive_record_count");
    }

    @Test
    @DisplayName("page size is clamped and page numbers are 1-based")
    void pagingBounds() {
        assertThat(DspmAssetQuery.cloud(null, null, null, 5000, null).pageSize())
                .isEqualTo(DspmAssetQuery.MAX_PAGE_SIZE);
        assertThat(DspmAssetQuery.cloud(null, null, null, 0, null).pageSize())
                .isEqualTo(DspmAssetQuery.DEFAULT_PAGE_SIZE);
        // The API clamps below 1 to 1; matching that keeps our own arithmetic consistent with the
        // pagination block it returns.
        assertThat(DspmAssetQuery.cloud(null, null, -3, null, null).page()).isEqualTo(1);
    }

    @Test
    @DisplayName("filters accept both repeated and comma-joined values, and drop unknown dimensions")
    void filterNormalisation() {
        DspmAssetQuery query = DspmAssetQuery.cloud(null, null, null, null, Map.of(
                "provider", List.of("S3", "EBS"),
                "severity", List.of("CRITICAL,HIGH"),
                // Not an accepted dimension — dropped rather than rejected, so a stale bookmark still
                // returns results instead of a 400.
                "not_a_dimension", List.of("x")));

        Map<String, List<String>> params = query.toFilterParams();
        assertThat(params.get("provider")).containsExactly("EBS,S3");
        assertThat(params.get("severity")).containsExactly("CRITICAL,HIGH");
        assertThat(params).doesNotContainKey("not_a_dimension");
    }

    @Test
    @DisplayName("the NONE severity sentinel is passed through so null-severity rows stay filterable")
    void severityNonePassesThrough() {
        DspmAssetQuery query = DspmAssetQuery.cloud(null, null, null, null,
                Map.of("severity", List.of("NONE")));

        // ?severity=NONE is what selects rows whose risk.level is null — 109 of 132 live.
        assertThat(query.toFilterParams().get("severity")).containsExactly("NONE");
    }

    @Test
    @DisplayName("equivalent queries produce one cache key regardless of filter order")
    void cacheKeyIsOrderIndependent() {
        DspmAssetQuery a = DspmAssetQuery.cloud(null, null, null, null,
                Map.of("provider", List.of("S3", "EBS")));
        DspmAssetQuery b = DspmAssetQuery.cloud(null, null, null, null,
                Map.of("provider", List.of("EBS", "S3")));

        // Otherwise two entries hold identical data and each miss costs a vendor request.
        assertThat(a.cacheFragment()).isEqualTo(b.cacheFragment());
    }

    @Test
    @DisplayName("the filter cache key ignores paging and sort, which the endpoint ignores too")
    void filterCacheKeyIgnoresPaging() {
        DspmAssetQuery page1 = DspmAssetQuery.cloud("bucket", "-risk_score", 1, 25, null);
        DspmAssetQuery page4 = DspmAssetQuery.cloud("bucket", "name", 4, 100, null);

        assertThat(page1.filterCacheFragment()).isEqualTo(page4.filterCacheFragment());
        // ...while the list key must still distinguish them.
        assertThat(page1.cacheFragment()).isNotEqualTo(page4.cacheFragment());
    }

    @Test
    @DisplayName("search text reaches the vendor as q")
    void searchMapsToQ() {
        assertThat(DspmAssetQuery.cloud("  xamops  ", null, null, null, null)
                .toFilterParams().get("q")).containsExactly("xamops");
        assertThat(DspmAssetQuery.cloud("   ", null, null, null, null)
                .toFilterParams()).doesNotContainKey("q");
    }
}
