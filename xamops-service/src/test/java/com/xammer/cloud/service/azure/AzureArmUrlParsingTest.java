package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovering the subscription and the RBAC action from an ARM URL.
 *
 * <p>{@code AzureArmClient.send} is handed only a URL and a bearer token, so when it sees a 403
 * it has no subscription id to attribute the gap to. Threading one through four public methods
 * and every caller would have been invasive; the URL already contains it. These tests pin that
 * parsing, because getting it wrong means a gap recorded against the wrong account — or, worse,
 * silently dropped.
 */
class AzureArmUrlParsingTest {

    // ── Subscription id ───────────────────────────────────────────────────────

    @Test
    void readsTheSubscriptionFromAResourceUrl() {
        String url = "https://management.azure.com/subscriptions/0000-1111-2222/resourceGroups/rg1"
                + "/providers/Microsoft.Compute/virtualMachines?api-version=2023-03-01";

        assertThat(AzureArmClient.subscriptionIdFrom(url)).isEqualTo("0000-1111-2222");
    }

    @Test
    @DisplayName("A subscription-scoped URL with a query string immediately after still parses")
    void readsTheSubscriptionWhenFollowedByAQuery() {
        String url = "https://management.azure.com/subscriptions/abc-123?api-version=2022-12-01";

        assertThat(AzureArmClient.subscriptionIdFrom(url)).isEqualTo("abc-123");
    }

    @Test
    void returnsNullWhenThereIsNoSubscriptionSegment() {
        assertThat(AzureArmClient.subscriptionIdFrom("https://management.azure.com/tenants")).isNull();
        assertThat(AzureArmClient.subscriptionIdFrom(null)).isNull();
    }

    // ── RBAC action ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("The action keeps the provider and one resource-type segment, which is what the catalog needs")
    void buildsTheActionFromTheProviderPath() {
        String url = "https://management.azure.com/subscriptions/s1/resourceGroups/rg1"
                + "/providers/Microsoft.Compute/virtualMachines?api-version=2023-03-01";

        assertThat(AzureArmClient.actionFrom(url)).isEqualTo("Microsoft.Compute/virtualMachines/read");
    }

    @Test
    void stopsAtTheSecondSegmentRatherThanSwallowingResourceNames() {
        String url = "https://management.azure.com/subscriptions/s1/resourceGroups/rg1"
                + "/providers/Microsoft.Storage/storageAccounts/mystorageacct/blobServices/default";

        // Not ".../storageAccounts/mystorageacct/..." — a specific resource name would make every
        // resource its own action key and defeat the registry's per-action folding.
        assertThat(AzureArmClient.actionFrom(url)).isEqualTo("Microsoft.Storage/storageAccounts/read");
    }

    @Test
    @DisplayName("A URL with no provider segment falls back to a subscription read")
    void fallsBackForSubscriptionScopedUrls() {
        assertThat(AzureArmClient.actionFrom("https://management.azure.com/subscriptions/s1?api-version=1"))
                .isEqualTo("Microsoft.Resources/subscriptions/read");
        assertThat(AzureArmClient.actionFrom(null))
                .isEqualTo("Microsoft.Resources/subscriptions/read");
    }

    @Test
    void handlesAProviderPathThatEndsImmediately() {
        String url = "https://management.azure.com/subscriptions/s1/providers/Microsoft.CostManagement";

        assertThat(AzureArmClient.actionFrom(url)).isEqualTo("Microsoft.CostManagement/read");
    }
}
