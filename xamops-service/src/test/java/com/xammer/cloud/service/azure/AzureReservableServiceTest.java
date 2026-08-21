package com.xammer.cloud.service.azure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which billing service lines may produce a reservation suggestion.
 *
 * <p>The recommendation builder used to walk every line on the bill and propose reserving it,
 * so a UAT subscription was shown "savings potential ~20%" cards for Bandwidth, Virtual
 * Network and Load Balancer. Azure sells no reservation for any of those, so the cards could
 * never be acted on and their invented savings inflated the headline figure.
 */
class AzureReservableServiceTest {

    private static boolean isReservable(String serviceName) throws Exception {
        Method m = AzureReservationService.class.getDeclaredMethod("isReservable", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, serviceName);
    }

    @Test
    @DisplayName("services Azure sells reservations for are eligible")
    void reservableServicesAreEligible() throws Exception {
        assertThat(isReservable("Virtual Machines")).isTrue();
        assertThat(isReservable("SQL Database")).isTrue();
        assertThat(isReservable("SQL Managed Instance")).isTrue();
        assertThat(isReservable("Azure Cosmos DB")).isTrue();
        assertThat(isReservable("Azure Cache for Redis")).isTrue();
        assertThat(isReservable("Azure Database for PostgreSQL")).isTrue();
        assertThat(isReservable("Azure Database for MySQL")).isTrue();
        assertThat(isReservable("App Service")).isTrue();
        assertThat(isReservable("Azure Data Explorer")).isTrue();
        assertThat(isReservable("Azure Databricks")).isTrue();
        // Blob and Files both sell reserved capacity.
        assertThat(isReservable("Storage")).isTrue();
    }

    @Test
    @DisplayName("services with no Azure reservation offering are rejected")
    void nonReservableServicesAreRejected() throws Exception {
        // These four are exactly what UAT displayed reservation cards for.
        assertThat(isReservable("Bandwidth")).isFalse();
        assertThat(isReservable("Virtual Network")).isFalse();
        assertThat(isReservable("Load Balancer")).isFalse();
        assertThat(isReservable("Public IP")).isFalse();

        assertThat(isReservable("Azure DNS")).isFalse();
        assertThat(isReservable("Key Vault")).isFalse();
        assertThat(isReservable("Container Registry")).isFalse();
        assertThat(isReservable("Azure Monitor")).isFalse();
    }

    @Test
    @DisplayName("a broad fragment cannot admit an explicitly non-reservable service")
    void explicitExclusionsWinOverFragments() throws Exception {
        // "storage" is a reservable fragment, but these two are not reservable.
        assertThat(isReservable("Storage Sync")).isFalse();
        assertThat(isReservable("Storage Explorer")).isFalse();
    }

    @Test
    @DisplayName("matching is case-insensitive and tolerates null or blank")
    void inputHandling() throws Exception {
        assertThat(isReservable("VIRTUAL MACHINES")).isTrue();
        assertThat(isReservable("virtual machines")).isTrue();
        assertThat(isReservable(null)).isFalse();
        assertThat(isReservable("")).isFalse();
        assertThat(isReservable("   ")).isFalse();
    }
}
