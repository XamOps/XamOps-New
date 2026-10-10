package com.xammer.cloud.service.automation.workload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two safety flags, both defaulting to <strong>off</strong>.
 *
 * <p>Primitive {@code boolean} with a {@code :false} placeholder default on each, so an absent
 * property, an empty property and a missing profile all read as "off" and "not ready". There is no
 * path by which omitting configuration turns this feature on.
 *
 * <p>Two {@code @Value} fields rather than one {@code @ConfigurationProperties} class because the
 * keys sit under <strong>two different prefixes</strong>, one of which is not ours:
 * {@code xamops.k8s.automation.readiness.*} is the platform-wide readiness namespace the CloudK8s
 * UI work also writes to, so the engine reads the UI's key rather than inventing a parallel one —
 * one flag then flips the UI and the engine together. When that branch's
 * {@code K8sAutomationReadinessProperties} bean lands, this binding can be swapped for it with no
 * behaviour change.
 */
@Component
public class WorkloadAutoscalingProperties {

    /** Master switch. While false the engine makes no AWS call and no cluster call at all. */
    public static final String ENABLED_KEY = "xamops.automation.vertical.enabled";

    /**
     * Readiness for the {@code vertical-rightsizing} control, shared with the UI's readiness map.
     * False until the engine's live validation has actually been performed against a cluster.
     */
    public static final String READINESS_KEY = "xamops.k8s.automation.readiness.vertical-rightsizing";

    private final boolean enabled;
    private final boolean ready;

    public WorkloadAutoscalingProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + READINESS_KEY + ":false}") boolean ready) {
        this.enabled = enabled;
        this.ready = ready;
    }

    /** Whether the engine may evaluate at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the engine has passed live validation. Independent of {@link #isEnabled()}: the engine
     * can be switched on to produce recommendations long before anything is allowed to apply them.
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * Whether an executor exists that could carry an apply at all.
     *
     * <p>A constant rather than a flag: no {@code WorkloadActionExecutor} implementation ships in
     * this release, which {@code ProductionClassScan} asserts by scanning the production classpath.
     * It is written as part of the expression below so that when an executor lands, the one place
     * that has to change is obvious — and so that no amount of configuration can turn auto-apply on
     * before then.
     */
    private static final boolean EXECUTOR_AVAILABLE = false;

    /**
     * Whether a decision may be applied automatically. Requires both flags <em>and</em> an executor,
     * of which there is none in this run — so this is false by construction, not by configuration.
     *
     * <p><strong>This is a live guard, not documentation.</strong>
     * {@code VerticalDecisionMapper.toDto} takes this object and publishes
     * {@code autoApplyEligible} as {@code engine AND this}, so a stored {@code AUTO} policy with a
     * clean recommendation still reaches the UI as not auto-appliable.
     */
    public boolean isAutoApplyPermitted() {
        return enabled && ready && EXECUTOR_AVAILABLE;
    }
}
