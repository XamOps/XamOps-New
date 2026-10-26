package com.xammer.cloud.service;

import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.ConsolidationPlanResponse;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.service.automation.workload.api.dto.NodeCostDto;
import com.xammer.cloud.service.automation.workload.cost.K8sCostProperties;
import com.xammer.cloud.service.automation.workload.cost.NodeRate;
import com.xammer.cloud.service.automation.workload.cost.NodeRateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The consolidation plan read: freshness, and savings that come only from {@link NodeRateResolver}
 * (never a fabricated or stale figure).
 */
class ConsolidationPlanServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:05:00Z");
    private static final String REGION = "ap-south-1";

    private AgentDataCacheRepository repository;
    private NodeRateResolver resolver;
    private ConsolidationPlanService service;
    private KubernetesClusterConfig config;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        repository = mock(AgentDataCacheRepository.class);
        resolver = mock(NodeRateResolver.class);
        K8sCostProperties cost = K8sCostProperties.of(true, Duration.ofHours(6), Duration.ofMinutes(15),
                Duration.ofSeconds(60), Duration.ofMinutes(30), 730, 1.0, 0.5, 10000);
        service = new ConsolidationPlanService(repository, resolver, cost, Clock.fixed(NOW, ZoneOffset.UTC));
        config = new KubernetesClusterConfig();
        config.setId(100L);
        config.setClusterName("prod-eks");
        config.setRegion(REGION);
        account = new CloudAccount();
        account.setId(7L);
    }

    // ── Plan JSON builders (shape of the Go golden plans) ─────────────────

    private static String node(String name, String instanceType, String capacityType, String zone, boolean fargate) {
        return "{\"name\":\"" + name + "\",\"nodeGroup\":\"ng-a\",\"instanceType\":\"" + instanceType
                + "\",\"capacityType\":\"" + capacityType + "\",\"zone\":\"" + zone + "\",\"fargate\":" + fargate
                + ",\"cpuRequestedPct\":10,\"memRequestedPct\":3.1}";
    }

    private static String move(String type, String node) {
        return "{\"type\":\"" + type + "\",\"node\":" + node + ",\"pods\":[]}";
    }

    private static String plan(String generatedAt, String... moves) {
        return "{\"schemaVersion\":1,\"generatedAt\":\"" + generatedAt + "\",\"mode\":\"analysis\","
                + "\"actor\":{\"kind\":\"NONE\",\"evidence\":[],\"note\":\"Display only.\"},"
                + "\"settings\":{\"underutilizedThresholdPct\":50,\"headroomPct\":15,\"maxNodesPerPlan\":100},"
                + "\"summary\":{\"nodesEvaluated\":" + moves.length + ",\"empty\":" + moves.length
                + ",\"drain\":0,\"blocked\":0,\"kept\":0},"
                + "\"moves\":[" + String.join(",", moves) + "],\"blocked\":[],\"kept\":[]}";
    }

    private void stored(String json) {
        AgentDataCache row = new AgentDataCache();
        row.setDataJson(json);
        when(repository.findByClusterConfigIdAndDataType(100L, "autoscaling-consolidation-plan"))
                .thenReturn(Optional.of(row));
    }

    private static NodeRate priced(double usdPerHour, String instanceType) {
        return NodeRate.priceable(usdPerHour, NodeRate.Source.PRICE_LIST, "live price", instanceType,
                "on_demand", "ap-south-1a", REGION);
    }

    // ── NO_PLAN / UNREADABLE ──────────────────────────────────────────────

    @Test
    void noRowOrNoConfigIsNoPlan() {
        when(repository.findByClusterConfigIdAndDataType(anyLong(), anyString())).thenReturn(Optional.empty());
        ConsolidationPlanResponse r = service.getPlan(config, account);
        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.NO_PLAN);
        assertThat(r.plan()).isNull();
        assertThat(r.savings()).isNull();
        assertThat(service.getPlan(null, account).freshness()).isEqualTo(ConsolidationPlanResponse.NO_PLAN);
        verifyNoInteractions(resolver);
    }

    @Test
    void malformedJsonIsUnreadable() {
        stored("{\"schemaVersion\": tru");
        ConsolidationPlanResponse r = service.getPlan(config, account);
        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.UNREADABLE);
        assertThat(r.plan()).isNull();
        assertThat(r.savings()).isNull();
        verifyNoInteractions(resolver);
    }

    // ── CURRENT ───────────────────────────────────────────────────────────

    @Test
    void twoPricedNodesProjectTheirMonthlySum() {
        stored(plan("2026-10-24T00:00:00Z",
                move("EMPTY", node("node-1", "m5.xlarge", "ON_DEMAND", "ap-south-1a", false)),
                move("DRAIN", node("node-2", "m5.large", "ON_DEMAND", "ap-south-1a", false))));
        when(resolver.resolve(account, "m5.xlarge", "on_demand", "ap-south-1a", REGION)).thenReturn(priced(0.2, "m5.xlarge"));
        when(resolver.resolve(account, "m5.large", "on_demand", "ap-south-1a", REGION)).thenReturn(priced(0.1, "m5.large"));

        ConsolidationPlanResponse r = service.getPlan(config, account);

        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.CURRENT);
        assertThat(r.generatedAt()).isEqualTo("2026-10-24T00:00:00Z");
        assertThat(r.ageSeconds()).isEqualTo(300L);
        assertThat(r.plan().moves()).hasSize(2);
        ConsolidationPlanResponse.Savings s = r.savings();
        assertThat(s.pricedNodes()).isEqualTo(2);
        assertThat(s.unpricedNodes()).isZero();
        assertThat(s.projectedMonthlySavings()).isCloseTo((0.2 + 0.1) * 730, within(1e-9));
        assertThat(s.pricedMonthlySubtotal()).isCloseTo((0.2 + 0.1) * 730, within(1e-9));
        assertThat(s.nodes()).extracting(NodeCostDto::nodeName).containsExactly("node-1", "node-2");
        assertThat(s.nodes().get(0).monthlyCost()).isCloseTo(0.2 * 730, within(1e-9));
        assertThat(s.nodes().get(0).source()).isEqualTo("PRICE_LIST");
        assertThat(s.nodes().get(0).reason()).isNull();
    }

    @Test
    void capacityTypeIsTrimmedAndLowercasedForTheResolver() {
        stored(plan("2026-10-24T00:00:00Z", move("EMPTY", node("node-1", "m5.xlarge", " ON_DEMAND ", "ap-south-1a", false))));
        when(resolver.resolve(any(), any(), any(), any(), any())).thenReturn(priced(0.2, "m5.xlarge"));
        service.getPlan(config, account);
        verify(resolver).resolve(account, "m5.xlarge", "on_demand", "ap-south-1a", REGION);
    }

    @Test
    void zeroMovesProjectZero() {
        stored(plan("2026-10-24T00:00:00Z"));
        ConsolidationPlanResponse r = service.getPlan(config, account);
        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.CURRENT);
        assertThat(r.savings().projectedMonthlySavings()).isEqualTo(0.0);
        assertThat(r.savings().pricedMonthlySubtotal()).isNull();
        assertThat(r.savings().pricedNodes()).isZero();
        assertThat(r.savings().unpricedNodes()).isZero();
        assertThat(r.savings().nodes()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void oneUnpriceableNodeNullsTheProjection() {
        stored(plan("2026-10-24T00:00:00Z",
                move("EMPTY", node("node-1", "m5.xlarge", "ON_DEMAND", "ap-south-1a", false)),
                move("DRAIN", node("node-2", "x9.weird", "ON_DEMAND", "ap-south-1a", false))));
        when(resolver.resolve(account, "m5.xlarge", "on_demand", "ap-south-1a", REGION)).thenReturn(priced(0.2, "m5.xlarge"));
        when(resolver.resolve(account, "x9.weird", "on_demand", "ap-south-1a", REGION)).thenReturn(
                NodeRate.unpriceable(NodeRate.Reason.NO_LIVE_PRICE, "no live AWS price for x9.weird in ap-south-1",
                        "x9.weird", "on_demand", "ap-south-1a", REGION));

        ConsolidationPlanResponse.Savings s = service.getPlan(config, account).savings();

        assertThat(s.projectedMonthlySavings()).isNull();
        assertThat(s.pricedMonthlySubtotal()).isCloseTo(0.2 * 730, within(1e-9));
        assertThat(s.pricedNodes()).isEqualTo(1);
        assertThat(s.unpricedNodes()).isEqualTo(1);
        NodeCostDto unpriced = s.nodes().get(1);
        assertThat(unpriced.nodeName()).isEqualTo("node-2");
        assertThat(unpriced.monthlyCost()).isNull();
        assertThat(unpriced.source()).isNull();
        assertThat(unpriced.reason()).isEqualTo("NO_LIVE_PRICE");
    }

    @Test
    void pricingDisabledLeavesEveryNodeUnpriced() {
        stored(plan("2026-10-24T00:00:00Z",
                move("EMPTY", node("node-1", "m5.xlarge", "ON_DEMAND", "ap-south-1a", false)),
                move("DRAIN", node("node-2", "m5.large", "spot", "ap-south-1b", false))));
        when(resolver.resolve(any(), any(), any(), any(), any())).thenAnswer(inv -> NodeRate.unpriceable(
                NodeRate.Reason.PRICING_DISABLED, "cost pricing is disabled", inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));

        ConsolidationPlanResponse.Savings s = service.getPlan(config, account).savings();

        assertThat(s.projectedMonthlySavings()).isNull();
        assertThat(s.pricedMonthlySubtotal()).isNull();
        assertThat(s.pricedNodes()).isZero();
        assertThat(s.unpricedNodes()).isEqualTo(2);
        assertThat(s.nodes()).extracting(NodeCostDto::reason).containsOnly("PRICING_DISABLED");
        assertThat(s.nodes()).extracting(NodeCostDto::monthlyCost).containsOnlyNulls();
    }

    @Test
    void aFargateNodeIsUnpriceableWithoutAResolverCall() {
        stored(plan("2026-10-24T00:00:00Z", move("EMPTY", node("fargate-ip-1", "", "", "ap-south-1a", true))));

        ConsolidationPlanResponse.Savings s = service.getPlan(config, account).savings();

        assertThat(s.projectedMonthlySavings()).isNull();
        assertThat(s.unpricedNodes()).isEqualTo(1);
        assertThat(s.nodes().get(0).reason()).isEqualTo("FARGATE_UNPRICEABLE");
        assertThat(s.nodes().get(0).monthlyCost()).isNull();
        verify(resolver, never()).resolve(any(), any(), any(), any(), any());
    }

    // ── STALE ─────────────────────────────────────────────────────────────

    @Test
    void anElevenMinuteOldPlanIsStaleAndNeverPriced() {
        stored(plan("2026-10-23T23:54:00Z", move("EMPTY", node("node-1", "m5.xlarge", "ON_DEMAND", "ap-south-1a", false))));

        ConsolidationPlanResponse r = service.getPlan(config, account);

        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.STALE);
        assertThat(r.ageSeconds()).isEqualTo(660L);
        assertThat(r.plan()).isNotNull();
        assertThat(r.plan().moves()).hasSize(1);
        assertThat(r.savings()).isNull();
        verifyNoInteractions(resolver);
    }

    @Test
    void anUnparseableGeneratedAtIsStaleWithNoAge() {
        stored(plan("yesterday", move("EMPTY", node("node-1", "m5.xlarge", "ON_DEMAND", "ap-south-1a", false))));

        ConsolidationPlanResponse r = service.getPlan(config, account);

        assertThat(r.freshness()).isEqualTo(ConsolidationPlanResponse.STALE);
        assertThat(r.ageSeconds()).isNull();
        assertThat(r.savings()).isNull();
        verifyNoInteractions(resolver);
    }

    @Test
    void theAccountAndRegionReachTheResolver() {
        stored(plan("2026-10-24T00:00:00Z", move("EMPTY", node("node-1", "c6i.large", "spot", "ap-south-1b", false))));
        when(resolver.resolve(any(), any(), any(), any(), any())).thenReturn(priced(0.03, "c6i.large"));
        service.getPlan(config, account);
        verify(resolver).resolve(eq(account), eq("c6i.large"), eq("spot"), eq("ap-south-1b"), eq(REGION));
    }
}
