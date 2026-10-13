package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds the read service for tests, so the collaborator list lives in one place.
 *
 * <p>Public because the controller test is in another package. The defaults here are the
 * <em>inert</em> ones: a policy service with no stored rows, the real inventory and VPA readers
 * (neither performs I/O until it is handed a client), and the engine's clock pinned where a caller
 * asks for it.
 */
public final class VerticalReadServiceFixtures {

    /** Pinned so a {@code lastCycle} timestamp is assertable rather than merely non-null. */
    public static final Instant CYCLE_AT = Instant.parse("2026-10-11T09:30:00Z");

    private VerticalReadServiceFixtures() {
    }

    public static VerticalAutomationReadService service(WorkloadAutoscalingProperties properties,
                                                        UsageHistoryProvider usageProvider,
                                                        List<ClusterAccessProvider> accessProviders) {
        return service(properties, usageProvider, accessProviders, () -> 0L);
    }

    public static VerticalAutomationReadService service(WorkloadAutoscalingProperties properties,
                                                        UsageHistoryProvider usageProvider,
                                                        List<ClusterAccessProvider> accessProviders,
                                                        LongSupplier nanoClock) {
        return service(properties, metricsProperties("OFF", 24), usageProvider, accessProviders,
                new ClusterWorkloadInventory(), new UpstreamVpaReader(), noStoredPolicies(), nanoClock);
    }

    public static VerticalAutomationReadService service(WorkloadAutoscalingProperties properties,
                                                        VerticalMetricsProperties metricsProperties,
                                                        UsageHistoryProvider usageProvider,
                                                        List<ClusterAccessProvider> accessProviders,
                                                        ClusterWorkloadInventory inventory,
                                                        UpstreamVpaReader vpaReader,
                                                        WorkloadPolicyService policyService,
                                                        LongSupplier nanoClock) {
        return new VerticalAutomationReadService(properties, metricsProperties, usageProvider,
                accessProviders, inventory, vpaReader, policyService, noActions(),
                Clock.fixed(CYCLE_AT, ZoneOffset.UTC), nanoClock);
    }

    /** An action repository with no rows, so {@code lastAction} is null for every workload. */
    public static WorkloadVerticalActionRepository noActions() {
        WorkloadVerticalActionRepository actionRepository = mock(WorkloadVerticalActionRepository.class);
        when(actionRepository.findByClusterConfigIdOrderByAppliedAtDesc(any())).thenReturn(List.of());
        return actionRepository;
    }

    public static VerticalMetricsProperties metricsProperties(String defaultMode, int defaultWindowHours) {
        return new VerticalMetricsProperties("monitoring", "prometheus-server", 9090, 20, 120,
                defaultWindowHours, defaultMode);
    }

    public static WorkloadPolicyService noStoredPolicies() {
        return storedPolicies(Map.of());
    }

    public static WorkloadPolicyService storedPolicies(Map<String, WorkloadPolicy> rows) {
        WorkloadPolicyService policyService = mock(WorkloadPolicyService.class);
        when(policyService.resolveAll(any())).thenReturn(rows);
        return policyService;
    }
}
