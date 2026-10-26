package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.Move;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.NodeRef;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.service.automation.workload.api.dto.NodeCostDto;
import com.xammer.cloud.service.automation.workload.cost.K8sCostProperties;
import com.xammer.cloud.service.automation.workload.cost.NodeRate;
import com.xammer.cloud.service.automation.workload.cost.NodeRateResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Serves the look-only consolidation plan xamops-automation pushes (agent_data_cache, dataType
 * {@link EksAutoscalingService#DT_CONSOLIDATION_PLAN}) and prices the nodes its moves would remove.
 *
 * <p>
 * Savings come only from {@link NodeRateResolver} (the workload cost engine): a node it can't price
 * keeps a null monthly cost and its reason, and the projected total is then null rather than a
 * partial figure. A plan older than {@link #STALE_AFTER} (or with an unparseable generatedAt) is
 * served as STALE with no savings and no pricing call. Nothing here acts on the cluster.
 */
@Service
public class ConsolidationPlanService {

    private static final Logger logger = LoggerFactory.getLogger(ConsolidationPlanService.class);

    /** A plan older than this is shown, but never priced. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final AgentDataCacheRepository agentDataCacheRepository;
    private final NodeRateResolver nodeRateResolver;
    private final K8sCostProperties costProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConsolidationPlanService(AgentDataCacheRepository agentDataCacheRepository,
                                    NodeRateResolver nodeRateResolver,
                                    K8sCostProperties costProperties) {
        this(agentDataCacheRepository, nodeRateResolver, costProperties, Clock.systemUTC());
    }

    ConsolidationPlanService(AgentDataCacheRepository agentDataCacheRepository,
                             NodeRateResolver nodeRateResolver,
                             K8sCostProperties costProperties,
                             Clock clock) {
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.nodeRateResolver = nodeRateResolver;
        this.costProperties = costProperties;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** The latest plan for the guard-resolved cluster config, priced when current. */
    public ConsolidationPlanResponse getPlan(KubernetesClusterConfig config, CloudAccount account) {
        if (config == null) {
            return ConsolidationPlanResponse.noPlan();
        }
        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(config.getId(), EksAutoscalingService.DT_CONSOLIDATION_PLAN);
        if (cacheOpt.isEmpty()) {
            return ConsolidationPlanResponse.noPlan();
        }
        ConsolidationPlanDto plan;
        try {
            plan = objectMapper.readValue(cacheOpt.get().getDataJson(), ConsolidationPlanDto.class);
        } catch (Exception e) {
            // The message is logged, never the stored JSON, and nothing of it is returned.
            logger.warn("Consolidation plan for cluster config {} couldn't be parsed: {}", config.getId(),
                    e.getMessage());
            return ConsolidationPlanResponse.unreadable();
        }
        if (plan == null) {
            return ConsolidationPlanResponse.unreadable();
        }

        Instant generatedAt = parseTime(plan.generatedAt());
        Duration age = generatedAt == null ? null : Duration.between(generatedAt, clock.instant());
        Long ageSeconds = age == null ? null : age.getSeconds();
        if (age == null || age.compareTo(STALE_AFTER) > 0) {
            return new ConsolidationPlanResponse(ConsolidationPlanResponse.STALE, plan.generatedAt(), ageSeconds,
                    plan, null);
        }
        return new ConsolidationPlanResponse(ConsolidationPlanResponse.CURRENT, plan.generatedAt(), ageSeconds,
                plan, price(plan, account, config.getRegion()));
    }

    private ConsolidationPlanResponse.Savings price(ConsolidationPlanDto plan, CloudAccount account, String region) {
        List<NodeCostDto> nodes = new ArrayList<>();
        int priced = 0;
        double subtotal = 0.0;
        List<Move> moves = plan.moves() == null ? List.of() : plan.moves();
        for (Move move : moves) {
            NodeRef node = move.node();
            if (node == null) {
                continue;
            }
            NodeCostDto cost = toNodeCost(node, rateFor(node, account, region));
            nodes.add(cost);
            if (cost.monthlyCost() != null) {
                priced++;
                subtotal += cost.monthlyCost();
            }
        }
        int unpriced = nodes.size() - priced;
        Double projected = unpriced == 0 ? subtotal : null;
        Double pricedSubtotal = priced == 0 ? null : subtotal;
        return new ConsolidationPlanResponse.Savings(projected, pricedSubtotal, priced, unpriced, nodes);
    }

    private NodeRate rateFor(NodeRef node, CloudAccount account, String region) {
        String instanceType = blankToNull(node.instanceType());
        // Same normalization ClusterWorkloadInventory.nodeFacts applies to the capacity-type label.
        String capacityType = node.capacityType() == null || node.capacityType().isBlank() ? null
                : node.capacityType().trim().toLowerCase(Locale.ROOT);
        String zone = blankToNull(node.zone());
        if (node.fargate()) {
            return NodeRate.unpriceable(NodeRate.Reason.FARGATE_UNPRICEABLE,
                    "Fargate node is not priced per instance-type", instanceType, capacityType, zone, region);
        }
        return nodeRateResolver.resolve(account, instanceType, capacityType, zone, region);
    }

    /** As VerticalAutomationReadService.toNodeCost: usdPerHour × hoursPerMonth when priceable, else null. */
    private NodeCostDto toNodeCost(NodeRef node, NodeRate rate) {
        Double monthly = rate.priceable()
                ? rate.usdPerHour() * costProperties.hoursPerMonth()
                : null;
        String instanceType = rate.instanceType() != null ? rate.instanceType() : blankToNull(node.instanceType());
        String capacityType = rate.capacityType() != null ? rate.capacityType() : blankToNull(node.capacityType());
        String source = rate.source() == null ? null : rate.source().name();
        String reason = rate.reason() == null ? null : rate.reason().name();
        return new NodeCostDto(node.name(), instanceType, capacityType, rate.capacityTypeInferred(),
                monthly, source, reason, rate.note());
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
