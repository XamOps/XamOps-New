package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the label policy shared by the Resource Graph path and the per-type scan.
 *
 * <p>The labels are a contract rather than cosmetics: the cloudlist is grouped by them and the UI
 * renders its group headings and per-service icons from them. If the two enumeration paths disagreed,
 * one service would appear as two rows depending on which path last filled the cache — which is
 * exactly why {@code resolveGraphLabel} exists instead of a second copy of the maps inside
 * {@link AzureResourceGraphService}.
 */
class AzureGraphLabelTest {

    private AzureCloudListService service() {
        return new AzureCloudListService(
                Mockito.mock(AzureClientProvider.class),
                Mockito.mock(CloudAccountRepository.class),
                Mockito.mock(RedisCacheService.class),
                Mockito.mock(CachedDataRepository.class),
                new ObjectMapper(),
                Mockito.mock(AzureResourceGraphService.class));
    }

    @Test
    @DisplayName("types with a dedicated fetcher keep that fetcher's exact label")
    void dedicatedFetcherLabelsMatch() {
        var s = service();

        // These strings must match what the per-type fetchers hardcode, or the grouped view splits.
        assertThat(s.resolveGraphLabel("Microsoft.Compute/virtualMachines")).isEqualTo("Virtual Machines");
        assertThat(s.resolveGraphLabel("Microsoft.Storage/storageAccounts")).isEqualTo("Storage Accounts");
        assertThat(s.resolveGraphLabel("Microsoft.KeyVault/vaults")).isEqualTo("Key Vaults");
        assertThat(s.resolveGraphLabel("Microsoft.ContainerService/managedClusters"))
                .isEqualTo("Kubernetes Services");
        assertThat(s.resolveGraphLabel("Microsoft.DocumentDB/databaseAccounts")).isEqualTo("CosmosDB");
    }

    @Test
    @DisplayName("resolution is case-insensitive, since ARM casing varies by API version")
    void caseInsensitive() {
        var s = service();

        assertThat(s.resolveGraphLabel("microsoft.compute/virtualmachines")).isEqualTo("Virtual Machines");
        assertThat(s.resolveGraphLabel("MICROSOFT.COMPUTE/VIRTUALMACHINES")).isEqualTo("Virtual Machines");
    }

    @Test
    @DisplayName("noisy auto-generated types are dropped")
    void noiseIsDropped() {
        var s = service();

        assertThat(s.resolveGraphLabel("Microsoft.Insights/metricAlerts")).isNull();
        assertThat(s.resolveGraphLabel("Microsoft.Portal/dashboards")).isNull();
        assertThat(s.resolveGraphLabel("Microsoft.Compute/sshPublicKeys")).isNull();
    }

    @Test
    @DisplayName("child resources are dropped because their parent represents them")
    void childResourcesDropped() {
        // Two slashes: a sub-resource of a resource that is itself listed.
        assertThat(service().resolveGraphLabel("Microsoft.Storage/storageAccounts/blobServices")).isNull();
    }

    @Test
    @DisplayName("a nested type that is the resource itself survives the child-resource rule")
    void nestedButRealTypeSurvives() {
        // sql/servers/databases has two slashes but is the thing users care about, and it has a
        // dedicated fetcher. The dedicated-label lookup must therefore run before the slash rule.
        assertThat(service().resolveGraphLabel("Microsoft.Sql/servers/databases")).isEqualTo("SQL Databases");
    }

    @Test
    @DisplayName("curated names are used for types without a dedicated fetcher")
    void curatedNamesUsed() {
        var s = service();

        assertThat(s.resolveGraphLabel("Microsoft.App/containerApps")).isEqualTo("Container Apps");
        assertThat(s.resolveGraphLabel("Microsoft.DBforPostgreSQL/flexibleServers"))
                .isEqualTo("PostgreSQL Flexible Servers");
    }

    @Test
    @DisplayName("an unmapped type is derived rather than dropped")
    void unmappedTypeIsDerived() {
        String label = service().resolveGraphLabel("Microsoft.SomeNewService/widgets");

        // This is the coverage win: a type nobody mapped still gets a heading.
        assertThat(label).isNotBlank();
        assertThat(label).contains("Widgets");
    }

    @Test
    @DisplayName("a null or blank type is dropped rather than throwing")
    void blankTypeDropped() {
        var s = service();

        assertThat(s.resolveGraphLabel(null)).isNull();
        assertThat(s.resolveGraphLabel("")).isNull();
        assertThat(s.resolveGraphLabel("   ")).isNull();
    }
}
