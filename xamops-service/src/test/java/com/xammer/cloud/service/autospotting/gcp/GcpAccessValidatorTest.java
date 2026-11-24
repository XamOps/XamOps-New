package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.gcp.GcpClientProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each permission subset stores the right access type; a missing read permission refuses the connect (design-gcp.md §10.6). */
class GcpAccessValidatorTest {
    private static Set<String> read() {
        return new HashSet<>(GcpAccessValidator.READ_SET);
    }

    private static Set<String> autoSpotting() {
        Set<String> s = read();
        s.addAll(GcpAccessValidator.AUTOSPOTTING_SET);
        return s;
    }

    @Test
    void readSetOnlyIsReadOnly() {
        assertThat(GcpAccessValidator.classify(read()).accessType()).isEqualTo("READ_ONLY");
    }

    @Test
    void partOfTheAutoSpottingSetIsStillReadOnly() {
        Set<String> s = read();
        s.add("compute.instanceTemplates.create");
        assertThat(GcpAccessValidator.classify(s).accessType()).isEqualTo("READ_ONLY");
    }

    @Test
    void bothSetsIsAutoSpottingAndTheMarkerMakesItReadWrite() {
        Set<String> s = autoSpotting();
        assertThat(GcpAccessValidator.classify(s).accessType()).isEqualTo("AUTOSPOTTING");
        s.add(GcpAccessValidator.READ_WRITE_MARKER);
        assertThat(GcpAccessValidator.classify(s).accessType()).isEqualTo("READ_WRITE");
    }

    @Test
    void theOldRoleThatLacksPartOfTheCurrentVersionIsOutdated() {
        Set<String> s = autoSpotting();
        GcpAccessValidator.Result old = GcpAccessValidator.classify(s);
        assertThat(old.roleOutdated()).isTrue();
        assertThat(old.missingFromCurrentRole()).contains("compute.subnetworks.useExternalIp");
        s.addAll(GcpAccessValidator.currentRolePermissions());
        assertThat(GcpAccessValidator.classify(s).roleOutdated()).isFalse();
        assertThat(GcpAccessValidator.currentRolePermissions()).hasSize(14);
    }

    @Test
    void aMissingReadPermissionRefusesTheConnectAndNamesIt() {
        Set<String> s = autoSpotting();
        s.remove("compute.regions.get");
        assertThatThrownBy(() -> GcpAccessValidator.classify(s))
                .isInstanceOf(GcpAccessValidator.MissingReadPermissionException.class)
                .hasMessageContaining("compute.regions.get");
    }

    @Test
    void validateAsksTestIamPermissionsOnceThroughTheProvider() throws IOException {
        GcpClientProvider provider = mock(GcpClientProvider.class);
        Set<String> granted = autoSpotting();
        when(provider.testProjectPermissions(eq("key"), eq("p1"), any())).thenReturn(granted);
        assertThat(new GcpAccessValidator(provider).validate("key", "p1").accessType()).isEqualTo("AUTOSPOTTING");
        when(provider.testProjectPermissions(eq("key"), eq("p2"), any())).thenThrow(new IOException("boom"));
        assertThatThrownBy(() -> new GcpAccessValidator(provider).validate("key", "p2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("p2");
        assertThat(List.of(GcpAccessValidator.READ_SET)).isNotEmpty();
    }
}
