package com.xammer.cloud.onboarding;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto;
import com.xammer.cloud.service.onboarding.OnboardingStateDeriver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-6 (design §3.1): the full onboarding state table, first-match-wins, including UNKNOWN
 * propagation (rows 2, 5), the awaitingAgent window (row 3), the disabled read-only row (6), and
 * row 8 reading {@code derivedState} (not {@code effectiveActing}).
 */
class OnboardingStateDeriverTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private final OnboardingStateDeriver deriver =
            new OnboardingStateDeriver(Clock.fixed(NOW, ZoneOffset.UTC));

    private KubernetesClusterConfig config() {
        KubernetesClusterConfig c = new KubernetesClusterConfig();
        c.setId(1L);
        c.setCloudAccountId(7L);
        c.setClusterName("prod-eks");
        return c;
    }

    private static K8sCapabilitiesDto.XamopsAgent agent(String status) {
        return new K8sCapabilitiesDto.XamopsAgent(status, null, null);
    }

    private static K8sCapabilitiesDto.AutomationAgent automation(String status) {
        return new K8sCapabilitiesDto.AutomationAgent(status, null, null);
    }

    private static K8sCapabilitiesDto.NodeAutomation node(String derivedState) {
        return new K8sCapabilitiesDto.NodeAutomation(true, "full", false, derivedState, "ACTING");
    }

    private K8sCapabilitiesDto.Onboarding derive(KubernetesClusterConfig c, String agentStatus,
                                                 String automationStatus, String derivedState, boolean verified) {
        return deriver.derive(c, agent(agentStatus), automation(automationStatus), node(derivedState),
                verified, null, null);
    }

    @Test
    void row2_agentUnknown_isUnknown() {
        assertThat(derive(config(), "UNKNOWN", "NOT_INSTALLED", "OFF", false).state())
                .isEqualTo(OnboardingStateDeriver.UNKNOWN);
    }

    @Test
    void row3_notInstalledButRecentExchange_awaitingAgent() {
        KubernetesClusterConfig c = config();
        c.setLastExchangeAt(NOW.minusSeconds(120)); // within 60 min
        K8sCapabilitiesDto.Onboarding o = derive(c, "NOT_INSTALLED", "NOT_INSTALLED", "OFF", false);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.NOT_INSTALLED);
        assertThat(o.awaitingAgent()).isTrue();
    }

    @Test
    void row4_notInstalledNoRecentExchange_plainNotInstalled() {
        KubernetesClusterConfig c = config();
        c.setLastExchangeAt(NOW.minusSeconds(4000)); // older than 60 min
        K8sCapabilitiesDto.Onboarding o = derive(c, "NOT_INSTALLED", "NOT_INSTALLED", "OFF", false);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.NOT_INSTALLED);
        assertThat(o.awaitingAgent()).isFalse();
    }

    @Test
    void row5_automationUnknownNoKey_isUnknown() {
        K8sCapabilitiesDto.Onboarding o = derive(config(), "CONNECTED", "UNKNOWN", "OFF", false);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.UNKNOWN);
    }

    @Test
    void row5_automationUnknownWithKey_isAutomationEnabled() {
        KubernetesClusterConfig c = config();
        c.setAutomationAgentApiKey("xamops_auto");
        K8sCapabilitiesDto.Onboarding o = derive(c, "CONNECTED", "UNKNOWN", "ANALYSIS", false);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.AUTOMATION_ENABLED);
        assertThat(o.automationSubState()).isEqualTo("ANALYSIS");
    }

    @Test
    void row6_disabledAutomation_isConnectedReadOnlyStaleDisabled() {
        KubernetesClusterConfig c = config();
        c.setAutomationDisabledAt(NOW.minusSeconds(600));
        K8sCapabilitiesDto.Onboarding o = derive(c, "CONNECTED", "NOT_INSTALLED", "OFF", false);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.CONNECTED_READ_ONLY);
        assertThat(o.automationDisabled()).isTrue();
        assertThat(o.stale()).isTrue();
    }

    @Test
    void row7_connectedReadOnly_staleTracksAgent() {
        K8sCapabilitiesDto.Onboarding connected = derive(config(), "CONNECTED", "NOT_INSTALLED", "OFF", false);
        assertThat(connected.state()).isEqualTo(OnboardingStateDeriver.CONNECTED_READ_ONLY);
        assertThat(connected.stale()).isFalse();

        K8sCapabilitiesDto.Onboarding stale = derive(config(), "STALE", "NOT_INSTALLED", "OFF", false);
        assertThat(stale.state()).isEqualTo(OnboardingStateDeriver.CONNECTED_READ_ONLY);
        assertThat(stale.stale()).isTrue();
    }

    @Test
    void row8_automationConnected_isAutomationEnabled_readsDerivedState_andVerified() {
        K8sCapabilitiesDto.Onboarding o = derive(config(), "CONNECTED", "CONNECTED", "AUTOMATED", true);
        assertThat(o.state()).isEqualTo(OnboardingStateDeriver.AUTOMATION_ENABLED);
        assertThat(o.automationSubState()).isEqualTo("AUTOMATED"); // derivedState, not effectiveActing
        assertThat(o.automationVerified()).isTrue();
    }

    @Test
    void onboarding_surfacesStoredFacts() {
        KubernetesClusterConfig c = config();
        c.setIdentityMode("POD_IDENTITY");
        c.setNodeScaler("NONE");
        c.setLastAgentKeyScope("CLUSTER");
        c.setAutomationIdentitySeenAt(Instant.parse("2026-10-30T11:00:00Z"));
        K8sCapabilitiesDto.Onboarding o = derive(c, "CONNECTED", "NOT_INSTALLED", "OFF", false);
        assertThat(o.identityMode()).isEqualTo("POD_IDENTITY");
        assertThat(o.nodeScaler()).isEqualTo("NONE");
        assertThat(o.dataKeyScope()).isEqualTo("CLUSTER");
        assertThat(o.identitySeenAt()).isEqualTo("2026-10-30T11:00:00Z");
    }
}
