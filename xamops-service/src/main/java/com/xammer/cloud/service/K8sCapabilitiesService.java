package com.xammer.cloud.service;

import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.AutomationAgent;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.Cluster;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.Connection;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.DataSource;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.NodeAutomation;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.Readiness;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.UserCapability;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.VerticalEngine;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.WriteGate;
import com.xammer.cloud.dto.k8s.K8sCapabilitiesDto.XamopsAgent;
import com.xammer.cloud.exception.K8sAutomationReason;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentDataCacheRepository.TypeTimestamp;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.security.K8sAutomationAccessService.UserDecision;
import com.xammer.cloud.security.K8sAutomationAccessService.WriteDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Builds the capabilities response (design §6.2, §11.3, §11.4). Entitlement and the write gates
 * come from {@link K8sAutomationAccessService}, the same methods the request guard uses, so the
 * UI and the server can't disagree.
 *
 * <p>
 * <b>Clocks.</b> Agent-supplied {@code collectedAt} is UTC wall-clock time
 * ({@code atOffset(ZoneOffset.UTC)}); columns the server writes with {@code LocalDateTime.now()}
 * ({@code agent_events.created_at}) are in the JVM's zone ({@code atZone(ZoneId.systemDefault())}).
 * Thresholds compare {@link Instant}s against the injected clock, and every timestamp is served
 * as a UTC instant, so a development JVM in IST doesn't mark every agent 5.5 hours old.
 */
@Service
public class K8sCapabilitiesService {

    private static final Logger log = LoggerFactory.getLogger(K8sCapabilitiesService.class);

    /** The engine's own switch, read by name so there is no compile-time dependency on its classes (R9). */
    public static final String VERTICAL_ENGINE_ENABLED = "xamops.automation.vertical.enabled";

    static final Duration AGENT_CONNECTED = Duration.ofMinutes(15);
    static final Duration AUTOMATION_AGENT_CONNECTED = Duration.ofMinutes(5);
    static final Duration AUTOMATION_AGENT_LOOKBACK = Duration.ofHours(24);
    static final String AUTOMATION_AGENT_SUFFIX = "-autoscaler";
    static final String K8S_RESOURCES = "k8s-resources";

    private final K8sAutomationAccessService accessService;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final EksAutoscalingService autoscalingService;
    private final K8sAutomationReadinessProperties readiness;
    private final Environment environment;
    private final Clock clock;
    private final AtomicBoolean warnedUnparseableEngineFlag = new AtomicBoolean();

    @Autowired
    public K8sCapabilitiesService(K8sAutomationAccessService accessService,
                                  AgentDataCacheRepository agentDataCacheRepository,
                                  AgentEventRepository agentEventRepository,
                                  EksAutoscalingService autoscalingService,
                                  K8sAutomationReadinessProperties readiness,
                                  Environment environment) {
        this(accessService, agentDataCacheRepository, agentEventRepository, autoscalingService, readiness,
                environment, Clock.systemUTC());
    }

    /** Test constructor with a fixed clock (plan P10). */
    K8sCapabilitiesService(K8sAutomationAccessService accessService,
                           AgentDataCacheRepository agentDataCacheRepository,
                           AgentEventRepository agentEventRepository,
                           EksAutoscalingService autoscalingService,
                           K8sAutomationReadinessProperties readiness,
                           Environment environment,
                           Clock clock) {
        this.accessService = accessService;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.autoscalingService = autoscalingService;
        this.readiness = readiness;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * @throws com.xammer.cloud.exception.K8sAutomationRefusedException {@code 403 K8S_NO_ACCOUNT_ACCESS}
     *         when the caller holds no AWS account with this number
     */
    public K8sCapabilitiesDto capabilities(ClientUserDetails user, String awsAccountId) {
        List<CloudAccount> accounts = accessService.resolveAccounts(user, awsAccountId);
        Instant now = clock.instant();

        UserDecision userDecision = accessService.userDecision(user);
        WriteDecision topGate = accessService.writeDecision(user, accounts);

        List<KubernetesClusterConfig> configs = accessService.configsFor(accounts);
        Map<Long, List<TypeTimestamp>> rowsByConfig = loadRows(configs);

        List<Cluster> clusters = new ArrayList<>();
        for (KubernetesClusterConfig config : configs) {
            clusters.add(cluster(user, accounts, config, rowsByConfig.get(config.getId()), now));
        }

        return new K8sCapabilitiesDto(
                K8sCapabilitiesDto.SCHEMA_VERSION,
                awsAccountId.trim(),
                now.toString(),
                connection(accounts),
                new UserCapability(userDecision.canChangeAutomation(), names(userDecision.reasons()),
                        userDecision.detail() != null ? userDecision.detail().name() : null),
                writeGate(topGate),
                readinessMap(),
                new VerticalEngine(verticalEngineEnabled()),
                clusters);
    }

    // ── Per cluster ───────────────────────────────────────────────────────

    private Cluster cluster(ClientUserDetails user, List<CloudAccount> accounts, KubernetesClusterConfig config,
                            List<TypeTimestamp> rows, Instant now) {
        List<CloudAccount> gateRows = accounts.stream()
                .filter(a -> a.getId().equals(config.getCloudAccountId()))
                .findFirst()
                .map(List::of)
                .orElse(accounts);
        WriteDecision gate = accessService.writeDecision(user, gateRows);

        XamopsAgent agent;
        AutomationAgent automationAgent;
        List<DataSource> dataSources;
        if (rows == null) {
            // The batched cache query failed (logged in loadRows).
            agent = new XamopsAgent("UNKNOWN", null, null);
            automationAgent = new AutomationAgent("UNKNOWN", null, null);
            dataSources = List.of();
        } else {
            agent = xamopsAgent(rows, now);
            automationAgent = automationAgent(config, rows, now);
            dataSources = rows.stream()
                    .sorted(Comparator.comparing(TypeTimestamp::getDataType, Comparator.nullsLast(String::compareTo)))
                    .map(r -> new DataSource(r.getDataType(), utcFromAgent(r.getCollectedAt()), r.getItemCount(),
                            displayed(config, r.getDataType())))
                    .toList();
        }

        NodeAutomation nodeAutomation;
        try {
            EksAutoscalingService.NodeAutomationSummary s = autoscalingService.summarize(config);
            String derived = "NOT_INSTALLED".equals(automationAgent.status()) ? "NOT_INSTALLED" : s.derivedState();
            nodeAutomation = new NodeAutomation(s.enabled(), s.mode(), s.evictorAnalyseOnly(), derived);
        } catch (RuntimeException e) {
            log.warn("Capabilities: node automation summary failed for cluster config {}: {}", config.getId(),
                    e.getClass().getSimpleName());
            nodeAutomation = new NodeAutomation(Boolean.TRUE.equals(config.getAutoscalingEnabled()), null, null, "UNKNOWN");
        }

        return new Cluster(config.getClusterName(), true, connection(gateRows), writeGate(gate),
                config.getDataSourceMode(), agent, automationAgent, nodeAutomation, dataSources);
    }

    private Map<Long, List<TypeTimestamp>> loadRows(List<KubernetesClusterConfig> configs) {
        if (configs.isEmpty()) {
            return Map.of();
        }
        try {
            List<TypeTimestamp> rows = agentDataCacheRepository.findTypeTimestampsByClusterConfigIdIn(
                    configs.stream().map(KubernetesClusterConfig::getId).toList());
            Map<Long, List<TypeTimestamp>> byConfig = rows.stream()
                    .collect(Collectors.groupingBy(TypeTimestamp::getClusterConfigId));
            Map<Long, List<TypeTimestamp>> out = new LinkedHashMap<>();
            configs.forEach(c -> out.put(c.getId(), byConfig.getOrDefault(c.getId(), List.of())));
            return out;
        } catch (RuntimeException e) {
            log.warn("Capabilities: agent data lookup failed for {} cluster(s): {}", configs.size(),
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** The XamOps agent: the k8s-resources row (only the observer agent pushes it). */
    private static XamopsAgent xamopsAgent(List<TypeTimestamp> rows, Instant now) {
        Optional<TypeTimestamp> resources = rows.stream()
                .filter(r -> K8S_RESOURCES.equals(r.getDataType()) && r.getCollectedAt() != null)
                .findFirst();
        if (resources.isEmpty()) {
            return new XamopsAgent("NOT_INSTALLED", null, null);
        }
        Instant collected = resources.get().getCollectedAt().atOffset(ZoneOffset.UTC).toInstant();
        String status = !collected.isBefore(now.minus(AGENT_CONNECTED)) ? "CONNECTED" : "STALE";
        return new XamopsAgent(status, collected.toString(), resources.get().getAgentVersion());
    }

    /**
     * The automation agent: its newest heartbeat event within 24 h (connected within 5 min), else
     * a cache row it pushed (stale, with that time), else not installed. Never lastAgentCheckin,
     * which every agent's heartbeat refreshes (E25).
     */
    private AutomationAgent automationAgent(KubernetesClusterConfig config, List<TypeTimestamp> rows, Instant now) {
        try {
            LocalDateTime since = LocalDateTime.ofInstant(now.minus(AUTOMATION_AGENT_LOOKBACK), ZoneId.systemDefault());
            Optional<AgentEvent> event = agentEventRepository
                    .findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
                            config.getId(), AUTOMATION_AGENT_SUFFIX, since);
            if (event.isPresent() && event.get().getCreatedAt() != null) {
                Instant seen = event.get().getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
                String status = !seen.isBefore(now.minus(AUTOMATION_AGENT_CONNECTED)) ? "CONNECTED" : "STALE";
                return new AutomationAgent(status, seen.toString(), event.get().getAgentVersion());
            }
        } catch (RuntimeException e) {
            log.warn("Capabilities: automation agent lookup failed for cluster config {}: {}", config.getId(),
                    e.getClass().getSimpleName());
            return new AutomationAgent("UNKNOWN", null, null);
        }
        return rows.stream()
                .filter(r -> r.getAgentVersion() != null && r.getAgentVersion().endsWith(AUTOMATION_AGENT_SUFFIX)
                        && r.getCollectedAt() != null)
                .max(Comparator.comparing(TypeTimestamp::getCollectedAt))
                .map(r -> new AutomationAgent("STALE", utcFromAgent(r.getCollectedAt()), r.getAgentVersion()))
                .orElse(new AutomationAgent("NOT_INSTALLED", null, null));
    }

    private static boolean displayed(KubernetesClusterConfig config, String dataType) {
        if (dataType == null) {
            return false;
        }
        String type = dataType.toLowerCase(Locale.ROOT);
        if (type.startsWith("kubescape")) {
            return Boolean.TRUE.equals(config.getKubescapeEnabled());
        }
        if (type.startsWith("opencost")) {
            return Boolean.TRUE.equals(config.getOpencostEnabled());
        }
        if (type.startsWith("karpenter")) {
            return Boolean.TRUE.equals(config.getKarpenterEnabled());
        }
        return true;
    }

    // ── Shared pieces ─────────────────────────────────────────────────────

    /** Read-only if any row is; the first status that isn't CONNECTED wins. */
    private static Connection connection(List<CloudAccount> rows) {
        boolean readWrite = !rows.isEmpty() && rows.stream().allMatch(K8sAutomationAccessService::isReadWrite);
        String status = rows.stream()
                .filter(a -> !K8sAutomationAccessService.isConnected(a))
                .map(a -> a.getStatus() == null ? "UNKNOWN" : a.getStatus().toUpperCase(Locale.ROOT))
                .findFirst()
                .orElse("CONNECTED");
        return new Connection(readWrite ? "READ_WRITE" : "READ_ONLY", status);
    }

    private static WriteGate writeGate(WriteDecision decision) {
        return new WriteGate(decision.allowed(), names(decision.reasons()));
    }

    private Map<String, Readiness> readinessMap() {
        Map<String, Readiness> map = new LinkedHashMap<>();
        readiness.asMap().forEach((key, ready) -> map.put(key.key(), ready
                ? new Readiness(true, null, null)
                : new Readiness(false, key.reason().name(), key.message())));
        return map;
    }

    boolean verticalEngineEnabled() {
        try {
            return Boolean.TRUE.equals(environment.getProperty(VERTICAL_ENGINE_ENABLED, Boolean.class, false));
        } catch (RuntimeException e) {
            if (warnedUnparseableEngineFlag.compareAndSet(false, true)) {
                log.warn("{} isn't a boolean; treating the vertical engine as off", VERTICAL_ENGINE_ENABLED);
            }
            return false;
        }
    }

    private static List<String> names(List<K8sAutomationReason> reasons) {
        return reasons.stream().map(Enum::name).toList();
    }

    private static String utcFromAgent(LocalDateTime collectedAt) {
        return collectedAt == null ? null : collectedAt.atOffset(ZoneOffset.UTC).toInstant().toString();
    }
}
