package com.xammer.cloud.service.automation.proposal;

import com.xammer.cloud.dto.autoscaling.ProposalDtos.ManualCapabilityDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The capabilities {@code manual} object (design 3.8): availability, effective state, pending count. */
class ManualCapabilityServiceTest extends ProposalServiceTestBase {

    private ManualCapabilityService capability() {
        return new ManualCapabilityService(readiness, proposals, service, workloadProps, clock);
    }

    @Test
    void notAvailableUntilTheKeyIsReadyAKeyExistsAndTheAgentAdvertisedManualV1() {
        readiness.setManualApproval(false);
        ManualCapabilityDto d = capability().forCluster(reload(cluster));
        assertThat(d.available()).isFalse();
        assertThat(d.reason()).isEqualTo("CONTROLLER_NOT_READY");
        assertThat(d.mode()).isEqualTo("AUTONOMOUS");

        readiness.setManualApproval(true);
        assertThat(capability().forCluster(reload(cluster)).reason()).isEqualTo("AGENT_TOO_OLD");

        clusters.recordAgentFeatures(cluster.getId(), "manual-v1", clock.instant(), 1200);
        ManualCapabilityDto ok = capability().forCluster(reload(cluster));
        assertThat(ok.available()).isTrue();
        assertThat(ok.reason()).isNull();
        assertThat(ok.workloadProposals()).isTrue();

        clock.advance(Duration.ofHours(3));
        assertThat(capability().forCluster(reload(cluster)).reason()).as("features older than 2 hours").isEqualTo("AGENT_TOO_OLD");
    }

    @Test
    void aClusterWithoutAnAutomationKeyReportsTheLegacyKeyReason() {
        var legacy = newCluster("legacy");
        legacy.setAutomationAgentApiKey(null);
        clusters.saveAndFlush(legacy);
        assertThat(capability().forCluster(reload(legacy)).reason()).isEqualTo("AGENT_KEY_LEGACY");
    }

    @Test
    void itCarriesTheModeTheEffectiveStateAndThePendingCount() {
        makeManual(cluster);
        send(cluster, drainItem("n1", FP_A), drainItem("n2", FP_A));
        ManualCapabilityDto d = capability().forCluster(reload(cluster));
        assertThat(d.mode()).isEqualTo("MANUAL");
        assertThat(d.effective()).isEqualTo("active");
        assertThat(d.pending()).isEqualTo(2);
    }

    @Test
    void workloadProposalsMirrorTheEngineReadiness() {
        org.mockito.Mockito.when(workloadProps.isAutoApplyPermitted()).thenReturn(false);
        assertThat(capability().forCluster(reload(cluster)).workloadProposals()).isFalse();
    }
}
