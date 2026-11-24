package com.xammer.cloud.xampulse.service.query;

import com.xammer.cloud.xampulse.dto.XpAlertRule;
import com.xammer.cloud.xampulse.dto.XpAlertScope;
import com.xammer.cloud.xampulse.dto.XpCondition;
import com.xammer.cloud.xampulse.dto.XpGrouping;
import com.xammer.cloud.xampulse.dto.XpMetricFilter;
import com.xammer.cloud.xampulse.dto.XpRouting;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.alert.RuleCompiler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baselines, bands and normal-range rules all read a metric the way the pages show it. On real hosts CPU and memory
 * arrive as one series per state; reading "the metric" averaged the states into a constant.
 */
class BaselineSignalTest {

    private static final double EPS = 1e-12;

    @Test
    void cpuIsOneMinusIdleMemoryIsUsedDiskIsTheFullestMountLoadIsAsSent() {
        assertEquals(0.8, BaselineSignal.CPU.value(0.2), EPS);
        assertEquals(List.of(new MetricSpec.LabelFilter("state", "=", "idle")), BaselineSignal.CPU.filters());
        assertEquals(List.of(new MetricSpec.LabelFilter("state", "=", "used")), BaselineSignal.MEMORY.filters());
        assertTrue(BaselineSignal.DISK.maxOfSeries());
        assertEquals(List.of(), BaselineSignal.LOAD.filters());
        assertTrue(BaselineSignal.of("system.network.io").isEmpty(), "no normal range is learned for other metrics");
    }

    @Test
    void aBandIsClampedToWhatTheValueCanBeAndFlippedForAnIdleChart() {
        // Learned on CPU busy: 70%..110% clamps to 70%..100%; on a chart of idle that is 0%..30%.
        assertArrayEquals(new double[] {0.7, 1.0}, BaselineSignal.CPU.bandOnValue(0.7, 1.1), EPS);
        assertArrayEquals(new double[] {0.0, 0.30000000000000004}, BaselineSignal.CPU.bandForSeries(0.7, 1.1), EPS);
        assertArrayEquals(new double[] {0.0, 0.2}, BaselineSignal.MEMORY.bandForSeries(-0.1, 0.2), EPS);
        assertArrayEquals(new double[] {0.0, 7.5}, BaselineSignal.LOAD.bandOnValue(-1, 7.5), EPS, "load has no upper bound");
    }

    @Test
    void aMetricsChartGetsABandOnlyWhenItPlotsTheValueTheBaselineLearned() {
        List<String> perHost = List.of("instance_id");
        MetricSpec.LabelFilter idle = new MetricSpec.LabelFilter("state", "=", "idle");
        assertTrue(MetricQueryService.bandApplies(new MetricSpec("system.cpu.utilization", "avg", perHost, List.of(idle))));
        assertTrue(MetricQueryService.bandApplies(new MetricSpec("system.filesystem.utilization", "max", perHost, List.of())));
        assertTrue(MetricQueryService.bandApplies(new MetricSpec("system.cpu.load_average.1m", "avg", perHost, List.of())));
        // CPU with every state averaged, CPU split by state, a different state, disk averaged, no per-host line.
        assertFalse(MetricQueryService.bandApplies(new MetricSpec("system.cpu.utilization", "avg", perHost, List.of())));
        assertFalse(MetricQueryService.bandApplies(new MetricSpec("system.cpu.utilization", "avg", List.of("instance_id", "state"), List.of(idle))));
        assertFalse(MetricQueryService.bandApplies(new MetricSpec("system.memory.utilization", "avg", perHost,
                List.of(new MetricSpec.LabelFilter("state", "=", "free")))));
        assertFalse(MetricQueryService.bandApplies(new MetricSpec("system.filesystem.utilization", "avg", perHost, List.of())));
        assertFalse(MetricQueryService.bandApplies(new MetricSpec("system.cpu.load_average.1m", "avg", List.of(), List.of())));
    }

    @Test
    void normalRangeRulesAreRefusedWhereNoRangeIsLearnedAndReadCpuFromTheIdleSeries() {
        RuleCompiler compiler = new RuleCompiler();
        XamPulseException other = assertThrows(XamPulseException.class, () -> compiler.validate(rule(anomaly("system.network.io", null))));
        assertTrue(other.getMessage().contains("CPU, memory, disk and load"), other.getMessage());
        XamPulseException filtered = assertThrows(XamPulseException.class,
                () -> compiler.validate(rule(anomaly("system.cpu.utilization", List.of(new XpMetricFilter("state", "=", "steal"))))));
        assertTrue(filtered.getMessage().contains("remove the label filters"), filtered.getMessage());

        XpAlertRule cpu = rule(anomaly("system.cpu.utilization", null));
        compiler.validate(cpu);
        ScopedSql sql = compiler.compile(7L, cpu, Instant.parse("2026-11-24T10:00:00Z")).sql();
        assertTrue(sql.params().contains("state=idle"), "the rule reads the idle series, as the baseline does: " + sql.params());
        assertTrue(sql.sql().contains("avgMerge(avg_state)"), sql.sql());
    }

    private static XpCondition anomaly(String metric, List<XpMetricFilter> filters) {
        return new XpCondition("METRIC_ANOMALY", metric, filters, null, null, "15m", null, null, "5m", "KEEP", null);
    }

    private static XpAlertRule rule(XpCondition c) {
        return new XpAlertRule(null, null, "Unusual CPU", true, "HIGH", new XpAlertScope(List.of(7L), List.of("i-0abc1234"), null), c,
                new XpGrouping(List.of("instance_id"), "15m"), new XpRouting(List.of(1L), false, null));
    }
}
