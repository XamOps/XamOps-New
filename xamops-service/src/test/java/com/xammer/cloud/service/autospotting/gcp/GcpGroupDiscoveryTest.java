package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.discovery.DiscoveredGroup;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GCP discovery rules 1-5 (design-core.md §2.5, AC-SPN-12, AC-SPN-14). Read only. */
class GcpGroupDiscoveryTest {
    private static final String LEGACY_TPL = FakeGcp.P + "/global/instanceTemplates/web-tpl-1-spot-1700000000";
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final GroupLookup lookup = mock(GroupLookup.class);
    private final List<CloudAccount> rows = new ArrayList<>();

    private CloudAccount account(long id) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider("GCP");
        a.setGcpProjectId(FakeGcp.PROJECT);
        rows.add(a);
        when(accounts.findByAwsAccountIdOrGcpProjectId(anyString(), anyString())).thenReturn(List.copyOf(rows));
        return a;
    }

    private DiscoveredGroup.Result discover(FakeGcp gcp, CloudAccount a) {
        return new GcpGroupDiscovery(acct -> gcp.view(), lookup, accounts).discover(a, gcp.view());
    }

    private static FakeGcp legacyProject() {
        FakeGcp gcp = new FakeGcp();
        gcp.migs.add(FakeGcp.fixture("mig-legacy-converted.json"));
        gcp.templates.put(LEGACY_TPL, FakeGcp.fixture("template-legacy-spot.json"));
        return gcp;
    }

    @Test
    void theLegacyConvertedMigIsLegacyUnconfirmedWithAnInexactSnapshot() throws Exception {
        FakeGcp gcp = legacyProject();
        CloudAccount a = account(5L);
        DiscoveredGroup.Result r = discover(gcp, a);
        assertThat(r.groups()).hasSize(1);
        DiscoveredGroup g = r.groups().get(0);
        assertThat(g.matchedRule()).isEqualTo(2);
        assertThat(g.groupKey()).isEqualTo("xamops-test/us-central1-b/legacy-web-mig");
        assertThat(g.providerStableId()).isEqualTo("9900110044");
        assertThat(g.reconstructedSnapshot().exact()).isFalse();
        assertThat(g.reconstructedSnapshot().inexactReasons()).contains("RECONSTRUCTED_FROM_DISCOVERY");
        JsonNode payload = FakeGcp.JSON.readTree(g.reconstructedSnapshot().payloadJson());
        assertThat(payload.path("locationType").asText()).isEqualTo("ZONAL");
        assertThat(payload.path("zone").asText()).isEqualTo("us-central1-b");
        assertThat(g.hasMarker()).isTrue();
        assertThat(g.hasSpotShare()).isTrue();
        assertThat(r.ruleCounts()).containsEntry(2, 1);
        assertThat(gcp.calls).allMatch(c -> c.endsWith(".get") || c.endsWith(".aggregatedList"));
    }

    @Test
    void theLegacyDescriptionWithoutSpotProvisioningIsNotLegacy() {
        FakeGcp gcp = legacyProject();
        ((ObjectNode) gcp.templates.get(LEGACY_TPL).path("properties").path("scheduling"))
                .put("provisioningModel", "STANDARD").put("preemptible", false);
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(5, 1);
    }

    @Test
    void aCopyMarkerWithNoRowIsAnOrphanMarker() {
        FakeGcp gcp = legacyProject();
        ((ObjectNode) gcp.templates.get(LEGACY_TPL)).put("description", "XamOps AutoSpotting copy of web-tpl-1");
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).singleElement().satisfies(g -> {
            assertThat(g.matchedRule()).isEqualTo(3);
            assertThat(g.reason()).isEqualTo(DiscoveredGroup.ORPHAN_MARKER);
            assertThat(g.reconstructedSnapshot().exact()).isFalse();
        });
    }

    @Test
    void aSpotTemplateWithoutAMarkerIsExternalSpotWithNoRow() {
        FakeGcp gcp = legacyProject();
        ((ObjectNode) gcp.templates.get(LEGACY_TPL)).put("description", "customer spot");
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(4, 1);
    }

    @Test
    void namesAreHintsOnly() {
        FakeGcp gcp = FakeGcp.healthy();
        gcp.mig().put("name", "web-mig-spot");
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).isEmpty();
        assertThat(r.ruleCounts()).containsEntry(5, 1);
    }

    @Test
    void anExistingRowIsAnIdentityCheckOnly() {
        FakeGcp gcp = legacyProject();
        CloudAccount a = account(5L);
        SpotGroup row = new SpotGroup();
        row.setId(UUID.randomUUID());
        row.setProvider(Provider.GCP);
        row.setCloudAccountId(5L);
        row.setGroupKey("xamops-test/us-central1-b/legacy-web-mig");
        row.setState(GroupState.LEGACY_UNCONFIRMED);
        when(lookup.forAccount(eq(Provider.GCP), eq(5L))).thenReturn(List.of(row));
        DiscoveredGroup.Result r = discover(gcp, a);
        assertThat(r.groups()).singleElement().satisfies(g -> {
            assertThat(g.matchedRule()).isEqualTo(1);
            assertThat(g.reconstructedSnapshot()).isNull();
        });
    }

    @Test
    void aDuplicateProjectIdIsSkippedAndReported() {
        FakeGcp gcp = legacyProject();
        account(5L);
        CloudAccount second = account(9L);
        DiscoveredGroup.Result r = discover(gcp, second);
        assertThat(r.groups()).isEmpty();
        assertThat(r.failedLocations()).containsExactly("DUPLICATE_PROVIDER_ACCOUNT:xamops-test");
        assertThat(gcp.calls).as("nothing is read for a shared project").isEmpty();
    }

    @Test
    void anUnreadableProjectIsReportedNeverNoGroups() {
        FakeGcp gcp = legacyProject().fail("instanceGroupManagers.aggregatedList", ErrorKind.PERMISSION);
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).isEmpty();
        assertThat(r.failedLocations()).containsExactly("xamops-test");
    }

    @Test
    void anUnreadableTemplateReportsTheLocation() {
        FakeGcp gcp = legacyProject().fail("instanceTemplates.get", ErrorKind.TRANSIENT);
        DiscoveredGroup.Result r = discover(gcp, account(5L));
        assertThat(r.groups()).isEmpty();
        assertThat(r.failedLocations()).containsExactly("xamops-test/us-central1-b");
    }
}
