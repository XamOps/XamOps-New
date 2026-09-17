package com.xammer.cloud.service.gcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the label derivation that lets Cloud Asset Inventory replace the per-service scan.
 *
 * <p>The per-service scan could only see resource types someone had written a collector for, so a
 * project using Dataflow, Spanner or Filestore showed none of them. Asset Inventory returns every
 * indexed type, which only helps if an unmapped type gets a sensible heading instead of being dropped
 * or lumped under "Other" — that is what {@code friendlyType} is for, and it is the piece most likely
 * to quietly produce nonsense in the UI.
 */
class GcpAssetInventoryTypeTest {

    @Test
    @DisplayName("derives a service and kind from an unmapped asset type")
    void derivesServiceAndKind() {
        assertThat(GcpAssetInventoryService.friendlyType("dataflow.googleapis.com/Job"))
                .isEqualTo("Dataflow Job");
        assertThat(GcpAssetInventoryService.friendlyType("spanner.googleapis.com/Database"))
                .isEqualTo("Spanner Database");
        assertThat(GcpAssetInventoryService.friendlyType("file.googleapis.com/Instance"))
                .isEqualTo("Filestore Instance");
    }

    @Test
    @DisplayName("splits camel case in the resource kind")
    void splitsCamelCase() {
        assertThat(GcpAssetInventoryService.friendlyType("monitoring.googleapis.com/UptimeCheckConfig"))
                .isEqualTo("Monitoring Uptime Check Config");
    }

    @Test
    @DisplayName("does not repeat the service name in the kind")
    void avoidsRepetition() {
        // "Dataproc Dataproc" would be the naive concatenation.
        assertThat(GcpAssetInventoryService.friendlyType("dataproc.googleapis.com/Dataproc"))
                .isEqualTo("Dataproc");
    }

    @Test
    @DisplayName("an unknown service is title-cased rather than dropped")
    void unknownServiceStillLabelled() {
        String label = GcpAssetInventoryService.friendlyType("somenewservice.googleapis.com/Widget");

        // The point of the fallback: a type nobody has mapped still gets a usable heading.
        assertThat(label).isNotBlank();
        assertThat(label).contains("Widget");
    }

    @Test
    @DisplayName("malformed or absent asset types fall back to Other rather than throwing")
    void malformedTypesAreSafe() {
        assertThat(GcpAssetInventoryService.friendlyType(null)).isEqualTo("Other");
        assertThat(GcpAssetInventoryService.friendlyType("")).isEqualTo("Other");
        // No slash at all — still must not throw.
        assertThat(GcpAssetInventoryService.friendlyType("weird")).isNotBlank();
    }
}
