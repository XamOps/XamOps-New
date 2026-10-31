package com.xammer.cloud.autospotting.obs;

import com.xammer.cloud.config.AutoSpottingMetricsExportConfig;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10.2 / AC: on the CloudWatch export registry only {@code autospotting.*} meters pass the
 * filter and the {@code region} tag is dropped (design-ux-ops.md §6.3). The two {@link MeterFilter}s
 * the customizer installs are exercised directly against meter ids built on a throwaway
 * {@link SimpleMeterRegistry}, so no CloudWatch client or AWS credential is involved.
 */
class AutoSpottingMetricsExportConfigTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void onlyAutospottingMetersPassTheExportFilter() {
        MeterFilter denyUnless = MeterFilter.denyUnless(
                id -> id.getName().startsWith(AutoSpottingMetricsExportConfig.AUTOSPOTTING_PREFIX));

        Meter.Id autospotting = registry.counter("autospotting.conversions.started",
                "provider", "aws", "kind", "ENABLE").getId();
        Meter.Id jvm = registry.counter("jvm.memory.used").getId();
        Meter.Id http = registry.counter("http.server.requests").getId();

        assertThat(denyUnless.accept(autospotting)).isEqualTo(MeterFilterReply.NEUTRAL);
        assertThat(denyUnless.accept(jvm)).isEqualTo(MeterFilterReply.DENY);
        assertThat(denyUnless.accept(http)).isEqualTo(MeterFilterReply.DENY);
    }

    @Test
    void regionTagIsDroppedOnTheExportRegistry() {
        MeterFilter dropRegion = AutoSpottingMetricsExportConfig.dropRegionTag();

        Meter.Id withRegion = registry.counter("autospotting.interruptions",
                "provider", "aws", "region", "us-east-1", "source", "aws_itn").getId();
        Meter.Id mapped = dropRegion.map(withRegion);

        assertThat(mapped.getTags().stream().map(io.micrometer.core.instrument.Tag::getKey))
                .doesNotContain("region")
                .contains("provider", "source");
    }

    @Test
    void aMeterWithoutRegionIsUnchangedByTheRegionFilter() {
        MeterFilter dropRegion = AutoSpottingMetricsExportConfig.dropRegionTag();

        Meter.Id noRegion = registry.counter("autospotting.conversions.started",
                "provider", "aws", "kind", "ENABLE").getId();

        assertThat(dropRegion.map(noRegion)).isSameAs(noRegion);
    }
}
