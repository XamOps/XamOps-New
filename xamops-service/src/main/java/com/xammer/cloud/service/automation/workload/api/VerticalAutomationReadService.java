package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the read endpoint's payload.
 *
 * <h2>It never fabricates</h2>
 *
 * With the master switch off — the default in every profile — this makes <strong>no</strong> cluster
 * or AWS call: it returns engine status plus gate reasons and an <strong>empty</strong>
 * {@code workloads} array, and it makes no claim about cluster access it has not verified
 * ({@code clusterAccess: null}). With the switch on but no usage provider implemented, the stub
 * reports {@code NO_METRICS_SOURCE} and {@code workloads} stays empty.
 *
 * <p>There is deliberately no code path that emits a zero, a placeholder or an unevaluated workload.
 * The measured {@code 1Mi} fabricated baseline is exactly the failure being designed out, and the
 * way to design it out is for the fabricated value to have nowhere to come from.
 */
@Service
public class VerticalAutomationReadService {

    private static final Logger logger = LoggerFactory.getLogger(VerticalAutomationReadService.class);

    private static final String ACCESS_CONFIGURED = "CONFIGURED";
    private static final String ACCESS_NOT_CONFIGURED = "NOT_CONFIGURED";
    private static final String ACCESS_UNREACHABLE = "UNREACHABLE";
    private static final String PATH_DIRECT = "DIRECT";
    private static final String PATH_AGENT = "AGENT";
    private static final String PATH_NONE = "NONE";

    private final WorkloadAutoscalingProperties properties;
    private final UsageHistoryProvider usageProvider;
    private final List<ClusterAccessProvider> accessProviders;

    public VerticalAutomationReadService(WorkloadAutoscalingProperties properties,
                                         UsageHistoryProvider usageProvider,
                                         List<ClusterAccessProvider> accessProviders) {
        this.properties = properties;
        this.usageProvider = usageProvider;
        this.accessProviders = List.copyOf(accessProviders);
    }

    public VerticalEngineResponse read(ClusterTarget target, KubernetesClusterConfig config) {
        List<String> gateReasons = new ArrayList<>();

        if (!properties.isEnabled() || !properties.isReady()) {
            gateReasons.add(ReasonCode.CONTROLLER_NOT_READY.name());
        }

        // Whether anything can supply percentiles is knowable from the wired provider's name alone,
        // with no I/O: the stub's name is "none".
        boolean metricsAvailable = !"none".equals(usageProvider.sourceName());
        if (!metricsAvailable) {
            gateReasons.add(ReasonCode.NO_METRICS_SOURCE.name());
        }

        DirectPath direct = directPath(target);
        if (direct.reason() != null && !gateReasons.contains(direct.reason())) {
            gateReasons.add(direct.reason());
        }

        VerticalEngineResponse.AgentAvailability agent = agentPath(config);

        String executionPath;
        if (direct.available()) {
            executionPath = PATH_DIRECT;
        } else if (agent.available()) {
            executionPath = PATH_AGENT;
        } else {
            executionPath = PATH_NONE;
        }

        VerticalEngineResponse.EngineStatus engine = new VerticalEngineResponse.EngineStatus(
                properties.isEnabled(),
                properties.isReady(),
                direct.clusterAccess(),
                executionPath,
                new VerticalEngineResponse.ExecutionPathDetail(
                        new VerticalEngineResponse.PathAvailability(direct.available(), direct.reason()),
                        agent),
                new VerticalEngineResponse.MetricsSource(metricsAvailable,
                        metricsAvailable ? usageProvider.sourceName() : null),
                // No scheduled reconciler exists, so no cycle has ever run. Reporting a fabricated
                // timestamp here would be the same class of error as a fabricated 1Mi request.
                VerticalEngineResponse.LastCycle.never());

        // Workloads stay empty: evaluating one needs per-container usage percentiles, and nothing
        // can supply them yet. The engine is exercised over the measured fixtures in its own tests,
        // and the wire mapping in VerticalDecisionMapperTest.
        return new VerticalEngineResponse(engine, gateReasons, List.of());
    }

    /**
     * Whether the backend can reach the API server itself.
     *
     * <p><strong>While the master switch is off, no provider is opened and no claim is made</strong>
     * — {@code available: false} with a {@code null} reason and {@code clusterAccess: null}. Guessing
     * {@code CLUSTER_ACCESS_NOT_CONFIGURED} would send a customer to fix an access entry that may
     * already be correct, which is worse than saying nothing.
     */
    private DirectPath directPath(ClusterTarget target) {
        if (!properties.isEnabled()) {
            return new DirectPath(false, null, null);
        }
        Optional<ClusterAccessProvider> provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst();
        if (provider.isEmpty()) {
            return new DirectPath(false, ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.name(),
                    ACCESS_NOT_CONFIGURED);
        }
        try (ClusterAccess access = provider.get().open(target)) {
            logger.debug("Direct cluster access verified for {} via {}", target.describe(), access.providerName());
            return new DirectPath(true, null, ACCESS_CONFIGURED);
        } catch (ClusterAccessException e) {
            // The two typed kinds are the only outcomes, which is what makes this mapping total.
            String clusterAccess = e.kind() == ClusterAccessException.Kind.CLUSTER_UNREACHABLE
                    ? ACCESS_UNREACHABLE : ACCESS_NOT_CONFIGURED;
            return new DirectPath(false, e.reasonCode(), clusterAccess);
        }
    }

    /**
     * Whether a live in-cluster agent could carry commands.
     *
     * <p>Read entirely from the cluster-config row, with no I/O: the agent polls outbound-only, so
     * its last check-in is the only evidence available and the only evidence needed. Reuses the UI's
     * existing {@code AGENT_NOT_INSTALLED} rather than introducing a new shared code.
     */
    private VerticalEngineResponse.AgentAvailability agentPath(KubernetesClusterConfig config) {
        if (config == null) {
            return new VerticalEngineResponse.AgentAvailability(false, ReasonCode.AGENT_NOT_INSTALLED, null);
        }
        boolean active = config.isAgentActive();
        return new VerticalEngineResponse.AgentAvailability(active,
                active ? null : ReasonCode.AGENT_NOT_INSTALLED,
                config.getLastAgentCheckin() == null
                        ? null
                        : config.getLastAgentCheckin().toInstant(java.time.ZoneOffset.UTC));
    }

    /** @param clusterAccess null when the engine has not checked */
    private record DirectPath(boolean available, String reason, String clusterAccess) {
    }
}
