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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

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
 *
 * <h2>What it costs per request</h2>
 *
 * With the switch off: nothing. With it on, the direct-path probe is a real AWS call and a real TLS
 * handshake, so it is cached per cluster for {@link #PROBE_TTL} — this endpoint is a UI poll target,
 * and an uncached probe would sit on the latency path of every poll.
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

    /**
     * How long a direct-path probe result is reused.
     *
     * <p>Opening a provider is not free: the EKS path calls {@code describeCluster}, mints a token,
     * completes a TLS handshake and issues a {@code namespaces/kube-system} GET. Probing on open is
     * deliberate — it is what stops a caller receiving a {@code ClusterAccess} that fails on first
     * use — but this endpoint is a UI <em>poll</em> target, so without a cache every poll would put
     * an AWS API call and a handshake on its latency path. 60s is short enough that a customer
     * fixing an access entry sees the result on their next refresh rather than on a restart.
     *
     * <p>Failures are cached on the same terms, which also stops a broken or unroutable cluster
     * being re-dialled on every poll.
     */
    static final Duration PROBE_TTL = Duration.ofSeconds(60);

    private final WorkloadAutoscalingProperties properties;
    private final UsageHistoryProvider usageProvider;
    private final List<ClusterAccessProvider> accessProviders;
    private final LongSupplier nanoClock;

    /**
     * Keyed by {@code client.id} and {@code kubernetes_cluster_config.id} — the canonical pair — so
     * the key space is bounded by the registered-cluster table and one tenant's clusters can never
     * be served another tenant's probe result.
     */
    private final Map<String, ProbeEntry> probeCache = new ConcurrentHashMap<>();

    @Autowired
    public VerticalAutomationReadService(WorkloadAutoscalingProperties properties,
                                         UsageHistoryProvider usageProvider,
                                         List<ClusterAccessProvider> accessProviders) {
        this(properties, usageProvider, accessProviders, System::nanoTime);
    }

    /** @param nanoClock a monotonic nanosecond source; the seam the TTL is tested through */
    VerticalAutomationReadService(WorkloadAutoscalingProperties properties,
                                  UsageHistoryProvider usageProvider,
                                  List<ClusterAccessProvider> accessProviders,
                                  LongSupplier nanoClock) {
        this.properties = properties;
        this.usageProvider = usageProvider;
        this.nanoClock = nanoClock;
        // Sorted here rather than relying on the injected order. Spring does order a List<T>
        // injection point by @Order, but the two providers' supports() predicates overlap for
        // essentially every registered cluster, so which one serves a cluster is a decision and
        // must be visible as one in the code that makes it -- not an artefact of component scanning.
        List<ClusterAccessProvider> ordered = new ArrayList<>(accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.accessProviders = List.copyOf(ordered);
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
            // Nothing is opened and nothing is cached: with the switch off there is no claim to
            // remember.
            return new DirectPath(false, null, null);
        }
        String key = target.clientId() + ":" + target.clusterConfigId();
        long now = nanoClock.getAsLong();
        ProbeEntry cached = probeCache.get(key);
        if (cached != null && !cached.isExpiredAt(now)) {
            return cached.result();
        }
        DirectPath fresh = probe(target);
        probeCache.put(key, new ProbeEntry(fresh, now + PROBE_TTL.toNanos()));
        return fresh;
    }

    /** One actual provider open, with no caching of its own. */
    private DirectPath probe(ClusterTarget target) {
        // accessProviders is sorted by declared precedence in the constructor, so "first that
        // supports the target" is the EKS direct path whenever it applies, and the kubeconfig
        // fallback otherwise.
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

    /**
     * One cached probe result and the monotonic deadline it stops being usable at.
     *
     * <p>Deadline rather than "recorded at + TTL at read time" so a clock source cannot make an
     * entry live longer than it was granted.
     */
    private record ProbeEntry(DirectPath result, long expiresAtNanos) {

        private boolean isExpiredAt(long nowNanos) {
            // Subtraction, not comparison: nanoTime wraps, and a difference stays correct across it.
            return nowNanos - expiresAtNanos >= 0;
        }
    }
}
