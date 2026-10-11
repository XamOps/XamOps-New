package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The usage contract, and the stub that keeps the read endpoint honest.
 */
class UsageContractTest {

    private static final Instant END = Instant.parse("2026-10-10T06:00:00Z");
    private static final Instant START = END.minus(Duration.ofHours(24));

    // ---------------------------------------------------------------- PercentileSet

    @Test
    @DisplayName("at() returns the supplied percentile")
    void suppliedPercentilesAreReturned() {
        PercentileSet set = PercentileSet.of(10, 40, 50, 80, 120);

        assertThat(set.at(50)).isEqualTo(10);
        assertThat(set.at(95)).isEqualTo(50);
        assertThat(set.max()).isEqualTo(120);
        assertThat(set.has(99)).isTrue();
    }

    @Test
    @DisplayName("at() THROWS for a percentile that was not supplied; it never substitutes a neighbour")
    void anUnsuppliedPercentileThrows() {
        // A provider that computes only p50/p90/p95 must not be able to answer a p99 policy. The
        // whole value of reporting the percentile in the response is that it is the one used.
        PercentileSet partial = new PercentileSet(Map.of(50, 10L, 90, 40L, 95, 50L), null);

        PercentileUnavailableException failure =
                assertThrows(PercentileUnavailableException.class, () -> partial.at(99));

        assertThat(failure.requested()).isEqualTo(99);
        assertThat(failure.available()).containsExactlyInAnyOrder(50, 90, 95);
        assertThat(failure).hasMessageContaining("Substituting");
    }

    @Test
    @DisplayName("an empty percentile set is not an observation")
    void anEmptySetIsRejected() {
        assertThatThrownBy(() -> new PercentileSet(Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PercentileSet(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- ContainerUsageAggregate

    @Test
    @DisplayName("coverage and explicit window bounds are mandatory, so INSUFFICIENT_HISTORY is computed")
    void coverageAndWindowAreMandatory() {
        assertThatThrownBy(() -> new ContainerUsageAggregate("api", PercentileSet.single(95, 1),
                PercentileSet.single(95, 1), 0, 0, END, START, 1.0, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSUFFICIENT_HISTORY");

        assertThatThrownBy(() -> new ContainerUsageAggregate("api", PercentileSet.single(95, 1),
                PercentileSet.single(95, 1), 0, 0, START, END, 1.5, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleCoverage");
    }

    @Test
    @DisplayName("a recommendation's source is mandatory")
    void theSourceIsMandatory() {
        assertThatThrownBy(() -> new ContainerUsageAggregate("api", PercentileSet.single(95, 1),
                PercentileSet.single(95, 1), 0, 0, START, END, 1.0, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("where its numbers came from");
    }

    @Test
    @DisplayName("usage is per container, and the window is readable")
    void usageIsPerContainer() {
        ContainerUsageAggregate aggregate = new ContainerUsageAggregate("logshipper",
                PercentileSet.single(95, 8), PercentileSet.single(95, 40L * 1024 * 1024),
                0.0, 0, START, END, 0.94, "prometheus");

        assertThat(aggregate.containerName()).isEqualTo("logshipper");
        assertThat(aggregate.window()).isEqualTo(Duration.ofHours(24));
        assertThat(aggregate.sampleCoverage()).isEqualTo(0.94);
    }

    // ---------------------------------------------------------------- UsageQuery

    @Test
    @DisplayName("a query without container names is rejected")
    void aQueryMustNameItsContainers() {
        ClusterTarget target = new ClusterTarget(7L, 42L, "102227938259", "c", "ap-south-1");
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "api");

        assertThatThrownBy(() -> new UsageQuery(target, ref, List.of(), List.of("api-1"), 95, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applied to every container");
    }

    @Test
    @DisplayName("a query without owner-resolved pod names is rejected")
    void aQueryMustNameItsPods() {
        ClusterTarget target = new ClusterTarget(7L, 42L, "102227938259", "c", "ap-south-1");
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "api");

        // The Go rightsizer's pod=~"api-.*" is the failure this makes unrepresentable: a provider
        // cannot be handed a workload without also being handed the pods that belong to it.
        assertThatThrownBy(() -> new UsageQuery(target, ref, List.of("api"), List.of(), 95, 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pod=~");
    }

    @Test
    @DisplayName("a query carries a mandatory namespace, so an owner-scoped lookup is always possible")
    void aQueryIsNamespaceScoped() {
        assertThatThrownBy(() -> WorkloadRef.of(" ", "Deployment", "api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace is mandatory");
    }

    // ---------------------------------------------------------------- a source that has nothing

    @Test
    @DisplayName("a provider with no source reports NO_METRICS_SOURCE and never returns data")
    void aSourcelessProviderNeverFabricates() {
        NoMetricsSourceProvider none = new NoMetricsSourceProvider();
        ClusterTarget target = new ClusterTarget(7L, 42L, "102227938259", "c", "ap-south-1");
        UsageQuery query = new UsageQuery(target, WorkloadRef.of("team-a", "Deployment", "api"),
                List.of("api"), List.of("api-7f9c-xyz"), 95, 24);

        assertThat(none.sourceName()).isEqualTo("none");
        assertThat(none.reachable(null)).isFalse();

        UsageUnavailableException failure =
                assertThrows(UsageUnavailableException.class, () -> none.aggregates(null, query));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
        assertThat(failure.reasonCode()).isEqualTo("NO_METRICS_SOURCE");
    }

    @Test
    @DisplayName("both usage failure kinds map one-to-one onto a shared reason code")
    void bothKindsAreSharedReasonCodes() {
        for (UsageUnavailableException.Kind kind : UsageUnavailableException.Kind.values()) {
            assertThat(new UsageUnavailableException(kind, "x").reasonCode()).isEqualTo(kind.name());
        }
        assertThat(UsageUnavailableException.Kind.values()).hasSize(2);
    }

    @Test
    @DisplayName("the Prometheus provider is the ONLY usage source on the production classpath")
    void thePrometheusProviderIsTheOnlyProductionImplementation() {
        List<String> implementations = com.xammer.cloud.service.automation.workload.ProductionClassScan
                .implementationsOf(UsageHistoryProvider.class);

        assertThat(implementations)
                .as("A second production provider would make 'where did this number come from' a"
                        + " question the payload could not answer, and a provider that returned"
                        + " plausible-looking numbers would be worse than no feature at all. The"
                        + " never-fabricate fake now lives in test scope (NoMetricsSourceProvider).")
                .containsExactly(PrometheusUsageHistoryProvider.class.getName());
    }

    @Test
    @DisplayName("the production provider names its source, so a number always says where it came from")
    void theProductionProviderNamesItsSource() {
        assertThat(PrometheusUsageHistoryProvider.SOURCE_NAME).isEqualTo("prometheus-proxy");
    }
}
