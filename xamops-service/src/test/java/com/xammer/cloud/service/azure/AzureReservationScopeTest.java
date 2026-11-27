package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reservations and savings plans are read from Azure's tenant-level lists, so each one has to be
 * matched to the subscription by its applied scope.
 */
class AzureReservationScopeTest {

    private static final String SUB = "4d5d91d7-1399-4ba0-aab0-e727a408c1ee";
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    @DisplayName("Single scope applies only to the subscription it names")
    void singleScope() throws Exception {
        JsonNode mine = json("{\"appliedScopeType\":\"Single\",\"appliedScopes\":[\"/subscriptions/" + SUB.toUpperCase() + "\"]}");
        JsonNode other = json("{\"appliedScopeType\":\"Single\",\"appliedScopes\":[\"/subscriptions/00000000-0000-0000-0000-000000000000\"]}");
        JsonNode viaProperties = json("{\"appliedScopeType\":\"Single\",\"appliedScopeProperties\":"
                + "{\"resourceGroupId\":\"/subscriptions/" + SUB + "/resourceGroups/dev\"}}");

        assertThat(AzureReservationService.appliesToSubscription(mine, SUB)).isTrue();
        assertThat(AzureReservationService.appliesToSubscription(other, SUB)).isFalse();
        assertThat(AzureReservationService.appliesToSubscription(viaProperties, SUB)).isTrue();
    }

    @Test
    @DisplayName("Shared and management-group scopes may cover the subscription, so they are kept")
    void sharedScopesKept() throws Exception {
        assertThat(AzureReservationService.appliesToSubscription(json("{\"appliedScopeType\":\"Shared\"}"), SUB)).isTrue();
        assertThat(AzureReservationService.appliesToSubscription(
                json("{\"appliedScopeType\":\"ManagementGroup\"}"), SUB)).isTrue();
    }

    @Test
    @DisplayName("Utilisation prefers the 30-day aggregate, then the longest window under it")
    void utilisation() throws Exception {
        JsonNode all = json("{\"aggregates\":[{\"grain\":1,\"value\":100},{\"grain\":7,\"value\":92.04},"
                + "{\"grain\":30,\"value\":88.46}]}");
        JsonNode noThirty = json("{\"aggregates\":[{\"grain\":1,\"value\":100},{\"grain\":7,\"value\":92.04}]}");

        assertThat(AzureReservationService.utilizationPercent(all)).isEqualTo(88.5);
        assertThat(AzureReservationService.utilizationPercent(noThirty)).isEqualTo(92.0);
        assertThat(AzureReservationService.utilizationPercent(json("{}"))).isNull();
    }
}
