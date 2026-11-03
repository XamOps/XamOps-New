package com.xammer.cloud.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes the CloudWatch export registry for AutoSpotting (design-ux-ops.md §6.3, plan-phase-10.md
 * task 10.2). Spring Boot's {@code CloudWatchMetricsExportAutoConfiguration} creates the
 * {@link CloudWatchMeterRegistry}, the {@code CloudWatchConfig} (namespace, step, batch size from
 * {@code management.cloudwatch.metrics.export.*}) and the {@code CloudWatchAsyncClient}; this class
 * only customizes that registry:
 *
 * <ul>
 *   <li>{@link MeterFilter#denyUnless} so only {@code autospotting.*} meters are exported — JVM,
 *       HTTP, Tomcat and every other platform meter stays in the in-process registry and off
 *       CloudWatch, bounding the custom-metric spend (V-OBS-01);</li>
 *   <li>a {@code region}-dropping filter applied to the export registry <em>only</em>, so
 *       {@code region} stays visible at {@code /actuator/metrics} for debugging but is not a billed
 *       CloudWatch dimension (§6.1, §6.3).</li>
 * </ul>
 *
 * <p><b>PROD only.</b> The whole export path turns on only when
 * {@code management.cloudwatch.metrics.export.enabled=true}, which is set in {@code application-prod}
 * and nowhere else (base, local, uat and preprod keep it {@code false}). Off prod no
 * {@link CloudWatchMeterRegistry} bean exists, this customizer is never invoked, and no AWS
 * credential is required to boot. The class is also guarded by that property so it contributes
 * nothing to the context when export is off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(CloudWatchMeterRegistry.class)
@ConditionalOnProperty(prefix = "management.cloudwatch.metrics.export", name = "enabled",
        havingValue = "true")
public class AutoSpottingMetricsExportConfig {

    /** Prefix every exported AutoSpotting meter shares (design-ux-ops.md §6.3). */
    public static final String AUTOSPOTTING_PREFIX = "autospotting.";

    /** The dimension dropped on export only, to bound the CloudWatch series count (§6.1). */
    public static final String DROPPED_EXPORT_TAG = "region";

    /**
     * Applies the AutoSpotting export rules to the CloudWatch registry and no other registry. The
     * {@code denyUnless} keeps every non-AutoSpotting meter out of CloudWatch; the region filter is
     * ordered after it so it only ever sees {@code autospotting.*} meters.
     */
    @Bean
    public MeterRegistryCustomizer<CloudWatchMeterRegistry> autoSpottingCloudWatchCustomizer() {
        return registry -> registry.config()
                .meterFilter(MeterFilter.denyUnless(id -> id.getName().startsWith(AUTOSPOTTING_PREFIX)))
                .meterFilter(dropRegionTag());
    }

    /** A {@link MeterFilter} that strips the {@code region} tag from every meter it sees (export only). */
    public static MeterFilter dropRegionTag() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                boolean hasRegion = id.getTags().stream().anyMatch(t -> DROPPED_EXPORT_TAG.equals(t.getKey()));
                if (!hasRegion) {
                    return id;
                }
                var kept = id.getTags().stream()
                        .filter(t -> !DROPPED_EXPORT_TAG.equals(t.getKey()))
                        .toList();
                return id.replaceTags(kept);
            }
        };
    }
}
