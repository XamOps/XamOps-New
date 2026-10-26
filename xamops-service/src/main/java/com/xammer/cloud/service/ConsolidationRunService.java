package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanDto.NodeRef;
import com.xammer.cloud.dto.autoscaling.ConsolidationRunDto;
import com.xammer.cloud.dto.autoscaling.ConsolidationRunDto.ConsolidationNodeRun;
import com.xammer.cloud.dto.autoscaling.ConsolidationRunResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.service.automation.workload.api.dto.NodeCostDto;
import com.xammer.cloud.service.automation.workload.cost.K8sCostProperties;
import com.xammer.cloud.service.automation.workload.cost.NodeRate;
import com.xammer.cloud.service.automation.workload.cost.NodeRateResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Serves the consolidation controller's run history pushed by xamops-automation (agent_data_cache,
 * LIST collector dataType {@link EksAutoscalingService#DT_CONSOLIDATION_RUNS}) and prices the nodes
 * the newest run actually removed.
 *
 * <p>
 * The acting counterpart of {@link ConsolidationPlanService}: same lenient ObjectMapper
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES = false}), same pricing rule — realized savings come only from
 * {@link NodeRateResolver}, a node it can't price keeps a null monthly cost and its reason, and the
 * realized total is then null rather than a partial figure. Nothing here acts on the cluster.
 */
@Service
public class ConsolidationRunService {

    private static final Logger logger = LoggerFactory.getLogger(ConsolidationRunService.class);

    private final AgentDataCacheRepository agentDataCacheRepository;
    private final NodeRateResolver nodeRateResolver;
    private final K8sCostProperties costProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public ConsolidationRunService(AgentDataCacheRepository agentDataCacheRepository,
                                   NodeRateResolver nodeRateResolver,
                                   K8sCostProperties costProperties) {
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.nodeRateResolver = nodeRateResolver;
        this.costProperties = costProperties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** The stored run history for the guard-resolved cluster config, newest-run removals priced. */
    public ConsolidationRunResponse getRuns(KubernetesClusterConfig config, CloudAccount account) {
        if (config == null) {
            return ConsolidationRunResponse.noRuns();
        }
        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(config.getId(), EksAutoscalingService.DT_CONSOLIDATION_RUNS);
        if (cacheOpt.isEmpty()) {
            return ConsolidationRunResponse.noRuns();
        }
        List<ConsolidationRunDto> runs;
        try {
            runs = objectMapper.readValue(cacheOpt.get().getDataJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ConsolidationRunDto.class));
        } catch (Exception e) {
            // The message is logged, never the stored JSON, and nothing of it is returned.
            logger.warn("Consolidation runs for cluster config {} couldn't be parsed: {}", config.getId(),
                    e.getMessage());
            return ConsolidationRunResponse.unreadable();
        }
        if (runs == null) {
            return ConsolidationRunResponse.unreadable();
        }
        List<ConsolidationRunDto> history = newestFirst(runs);
        return new ConsolidationRunResponse(ConsolidationRunResponse.OK, history,
                priceRealized(latestRemovals(history), account, config.getRegion()));
    }

    /** The list reversed when the agent appends oldest-first; a single run or empty list is unchanged. */
    private static List<ConsolidationRunDto> newestFirst(List<ConsolidationRunDto> runs) {
        List<ConsolidationRunDto> out = new ArrayList<>(runs);
        java.util.Collections.reverse(out);
        return out;
    }

    /** The removed-node runs of the newest run, or an empty list when there is none. */
    private static List<ConsolidationNodeRun> latestRemovals(List<ConsolidationRunDto> history) {
        if (history.isEmpty()) {
            return List.of();
        }
        ConsolidationRunDto latest = history.get(0);
        List<ConsolidationNodeRun> nodes = latest.nodes() == null ? List.of() : latest.nodes();
        List<ConsolidationNodeRun> removed = new ArrayList<>();
        for (ConsolidationNodeRun n : nodes) {
            if (ConsolidationRunDto.STATUS_REMOVED.equals(n.status())) {
                removed.add(n);
            }
        }
        return removed;
    }

    private ConsolidationRunResponse.RealizedSavings priceRealized(List<ConsolidationNodeRun> removed,
                                                                   CloudAccount account, String region) {
        if (removed.isEmpty()) {
            return null;
        }
        List<NodeCostDto> nodes = new ArrayList<>();
        int priced = 0;
        double subtotal = 0.0;
        for (ConsolidationNodeRun run : removed) {
            NodeRef node = run.node();
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
        if (nodes.isEmpty()) {
            return null;
        }
        int unpriced = nodes.size() - priced;
        Double realized = unpriced == 0 ? subtotal : null;
        Double pricedSubtotal = priced == 0 ? null : subtotal;
        return new ConsolidationRunResponse.RealizedSavings(realized, pricedSubtotal, priced, unpriced, nodes);
    }

    private NodeRate rateFor(NodeRef node, CloudAccount account, String region) {
        String instanceType = blankToNull(node.instanceType());
        String capacityType = node.capacityType() == null || node.capacityType().isBlank() ? null
                : node.capacityType().trim().toLowerCase(Locale.ROOT);
        String zone = blankToNull(node.zone());
        if (node.fargate()) {
            return NodeRate.unpriceable(NodeRate.Reason.FARGATE_UNPRICEABLE,
                    "Fargate node is not priced per instance-type", instanceType, capacityType, zone, region);
        }
        return nodeRateResolver.resolve(account, instanceType, capacityType, zone, region);
    }

    /** usdPerHour × hoursPerMonth when priceable, else null (mirrors ConsolidationPlanService). */
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
