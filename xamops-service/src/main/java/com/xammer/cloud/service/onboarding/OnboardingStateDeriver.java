package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The onboarding state machine (design §3.1) — a pure function. First match wins across the eight
 * rows; row 8 reads {@code nodeAutomation.derivedState} (NOT {@code effectiveActing}, which is the
 * orthogonal runtime-truth axis). No endpoint "sets" a state; transitions are caused only by
 * external facts (exchange, first push, the automation agent's first heartbeat, revoke/disable/offboard).
 */
@Service
public class OnboardingStateDeriver {

    // States (design §3.1 / §3.2).
    public static final String NOT_INSTALLED = "NOT_INSTALLED";
    public static final String CONNECTED_READ_ONLY = "CONNECTED_READ_ONLY";
    public static final String AUTOMATION_ENABLED = "AUTOMATION_ENABLED";
    public static final String UNKNOWN = "UNKNOWN";

    /** Row 3 "awaitingAgent" window after a successful exchange. */
    static final Duration AWAITING_AGENT_WINDOW = Duration.ofMinutes(60);

    private final Clock clock;

    public OnboardingStateDeriver(Clock clock) {
        this.clock = clock;
    }

    /**
     * Derive the onboarding view. {@code verified} is supplied by the caller
     * ({@code clusterAutomationVerified(config, now)}, §3.4) so this stays a pure function and does
     * not reach into the gate service.
     */
    public K8sCapabilitiesDto.Onboarding derive(KubernetesClusterConfig config,
                                                K8sCapabilitiesDto.XamopsAgent agent,
                                                K8sCapabilitiesDto.AutomationAgent automationAgent,
                                                K8sCapabilitiesDto.NodeAutomation nodeAutomation,
                                                boolean verified,
                                                K8sCapabilitiesDto.KeyMeta dataKey,
                                                K8sCapabilitiesDto.KeyMeta automationKey) {
        Instant now = Instant.now(clock);
        String agentStatus = agent == null ? "NOT_INSTALLED" : agent.status();
        String automationStatus = automationAgent == null ? "NOT_INSTALLED" : automationAgent.status();
        boolean hasAutomationKey = config.getAutomationAgentApiKey() != null
                && !config.getAutomationAgentApiKey().isEmpty();
        boolean agentStale = "STALE".equals(agentStatus);

        String state;
        boolean awaitingAgent = false;
        boolean automationDisabled = false;
        String automationSubState = null;
        boolean automationVerified = false;
        boolean stale = false;

        if ("UNKNOWN".equals(agentStatus)) {
            // Row 2.
            state = UNKNOWN;
        } else if ("NOT_INSTALLED".equals(agentStatus)) {
            // Rows 3 / 4.
            Instant lastExchange = config.getLastExchangeAt();
            if (lastExchange != null && lastExchange.isAfter(now.minus(AWAITING_AGENT_WINDOW))) {
                state = NOT_INSTALLED;
                awaitingAgent = true;
            } else {
                state = NOT_INSTALLED;
            }
        } else if ("UNKNOWN".equals(automationStatus) && !hasAutomationKey) {
            // Row 5: a lookup error must not show a read-only cluster as automated (F-24).
            state = UNKNOWN;
        } else if ("NOT_INSTALLED".equals(automationStatus) && config.getAutomationDisabledAt() != null) {
            // Row 6.
            state = CONNECTED_READ_ONLY;
            automationDisabled = true;
            stale = true;
        } else if ("NOT_INSTALLED".equals(automationStatus)) {
            // Row 7.
            state = CONNECTED_READ_ONLY;
            stale = agentStale;
        } else {
            // Row 8: automation agent CONNECTED / STALE, or UNKNOWN with an automation key.
            state = AUTOMATION_ENABLED;
            automationSubState = nodeAutomation == null ? null : nodeAutomation.derivedState();
            automationVerified = verified;
            stale = agentStale || "STALE".equals(automationStatus);
        }

        return new K8sCapabilitiesDto.Onboarding(
                state,
                awaitingAgent,
                stale,
                automationDisabled,
                automationSubState,
                automationVerified,
                instantToIso(config.getAutomationIdentitySeenAt()),
                config.getIdentityMode(),
                config.getNodeScaler(),
                config.getLastAgentKeyScope(),
                dataKey,
                automationKey);
    }

    private static String instantToIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
