package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.execute.WorkloadActionExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * The horizontal (HPA) master switch, parallel to {@link #ENABLED_KEY}. While false the read
     * path emits {@code NOT_EVALUATED} for every HPA decision, so the engine's real decisions stay
     * invisible until the feature is deliberately turned on — UAT only.
     */
    public static final String HORIZONTAL_ENABLED_KEY = "xamops.automation.horizontal.enabled";

    private final boolean enabled;
    private final boolean ready;
    private final boolean executorPresent;
    private final boolean horizontalEnabled;

    /**
     * The production binding.
     *
     * <p>{@code executorPresent} is answered by asking the container whether a
     * {@link WorkloadActionExecutor} bean exists, which is a real question now that one ships. An
     * {@link ObjectProvider} rather than a constructor-injected bean so this stays resolvable when
     * none is registered — and so no executor can depend on this class without a cycle.
     */
    @Autowired
    public WorkloadAutoscalingProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + READINESS_KEY + ":false}") boolean ready,
            @Value("${" + HORIZONTAL_ENABLED_KEY + ":false}") boolean horizontalEnabled,
            ObjectProvider<WorkloadActionExecutor> executors) {
        this(enabled, ready, executors.stream().findAny().isPresent(), horizontalEnabled);
    }

    /** No executor, horizontal off: the shape every test that is not about those wants. */
    public WorkloadAutoscalingProperties(boolean enabled, boolean ready) {
        this(enabled, ready, false, false);
    }

    public WorkloadAutoscalingProperties(boolean enabled, boolean ready, boolean executorPresent) {
        this(enabled, ready, executorPresent, false);
    }

    public WorkloadAutoscalingProperties(boolean enabled, boolean ready, boolean executorPresent,
                                         boolean horizontalEnabled) {
        this.enabled = enabled;
        this.ready = ready;
        this.executorPresent = executorPresent;
        this.horizontalEnabled = horizontalEnabled;
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
     * <p>This used to be the constant {@code false}, because no {@code WorkloadActionExecutor}
     * implementation shipped: the absence of a collaborator, rather than a flag that could be
     * mis-evaluated, was what made an apply impossible. One ships now
     * ({@code DirectInPlaceResizeExecutor}), so the question is answered by asking the container,
     * and a build that removes the executor goes back to answering false on its own.
     */
    public boolean isExecutorPresent() {
        return executorPresent;
    }

    /**
     * Whether a decision may be applied automatically. Requires both flags <em>and</em> an executor
     * — three independent conditions, none of which is documentation.
     *
     * <p><strong>This is a live guard.</strong> {@code VerticalDecisionMapper.toDto} takes this
     * object and publishes {@code autoApplyEligible} as {@code engine AND this}, so a stored
     * {@code AUTO} policy with a clean recommendation still reaches the UI as not auto-appliable
     * whenever any one of the three is missing.
     */
    public boolean isAutoApplyPermitted() {
        return enabled && ready && executorPresent;
    }

    /**
     * Whether the HPA (horizontal) decisions are surfaced on the read path. While false the mapper
     * downgrades every HPA decision to {@code NOT_EVALUATED}, so a UI built today keeps working.
     */
    public boolean isHorizontalEnabled() {
        return horizontalEnabled;
    }
}
