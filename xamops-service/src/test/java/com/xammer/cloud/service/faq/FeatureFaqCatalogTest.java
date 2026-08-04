package com.xammer.cloud.service.faq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFaqCatalogTest {

    // Mirrors frontend-app/src/constants/xamopsTabs.ts ALL_XAMOPS_TAB_KEYS.
    // Kept as a literal list (not shared code — frontend TS and backend Java
    // can't share constants) so this test fails loudly if the two drift.
    private static final List<String> EXPECTED_TAB_KEYS = List.of(
            "account-dashboard",
            "finops-cost", "finops-reservations", "finops-reports", "finops-waste",
            "finops-rightsizing", "finops-disk-rightsizing", "finops-cloudsitter",
            "finops-flex-compute", "finops-autospotting",
            "cloudops-cloudk8s", "cloudops-code-quality", "cloudops-cicd",
            "cloudops-devops-box", "cloudops-aiops", "cloudops-dbops",
            "secops-security-center", "secops-war", "secops-xamsecure", "secops-compliance",
            "secops-performance", "secops-alerts",
            "obs-overview", "obs-logs", "obs-metrics", "obs-traces", "obs-alerts",
            "obs-setup", "obs-grafana",
            "sre-dashboard", "sre-investigations", "sre-setup",
            "mediaops-overview", "mediaops-transcode", "mediaops-streams",
            "mediaops-storage", "mediaops-cost-savings",
            "licenses-marketops", "licenses-tools",
            "xam-tickets-technical", "xam-tickets-account-billing"
    );

    // Module-level catch-all entries (tabKey == the module's own key from
    // XAMOPS_MODULES, e.g. "finops" — never a leaf tab key, so no collision
    // with EXPECTED_TAB_KEYS above). No entry for "dashboard": it's a
    // single-tab module already covered by account-dashboard.
    private static final List<String> EXPECTED_MODULE_KEYS = List.of(
            "finops", "cloudops", "secops", "observability", "sre", "mediaops", "licenses", "tickets"
    );

    @Test
    void catalog_coversEveryExpectedTabKeyAndModuleKey() {
        Set<String> actualKeys = FeatureFaqCatalog.ENTRIES.stream()
                .map(FeatureFaqEntry::tabKey)
                .collect(Collectors.toSet());

        List<String> expected = new java.util.ArrayList<>(EXPECTED_TAB_KEYS);
        expected.addAll(EXPECTED_MODULE_KEYS);

        assertThat(actualKeys).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void catalog_hasNoDuplicateTabKeys() {
        List<String> keys = FeatureFaqCatalog.ENTRIES.stream()
                .map(FeatureFaqEntry::tabKey)
                .toList();

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void catalog_everyEntryHasNonBlankDescriptionAndAtLeastThreeKeywords() {
        for (FeatureFaqEntry entry : FeatureFaqCatalog.ENTRIES) {
            assertThat(entry.description())
                    .as("description for %s", entry.tabKey())
                    .isNotBlank();
            assertThat(entry.keywords())
                    .as("keywords for %s", entry.tabKey())
                    .hasSizeGreaterThanOrEqualTo(3);
            assertThat(entry.moduleLabel()).as("moduleLabel for %s", entry.tabKey()).isNotBlank();
            assertThat(entry.tabLabel()).as("tabLabel for %s", entry.tabKey()).isNotBlank();
        }
    }
}
