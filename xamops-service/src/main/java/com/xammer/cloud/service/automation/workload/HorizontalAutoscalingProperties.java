package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.execute.HpaApplyExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The HPA (horizontal) safety flags, mirroring {@link WorkloadAutoscalingProperties} — all defaulting
 * to <strong>off</strong>.
 *
 * <p>Primitive {@code boolean} with a {@code :false} placeholder default on each, so an absent
 * property, an empty property and a missing profile all read as "off" and "not ready". There is no
 * path by which omitting configuration turns the HPA apply path on.
 *
 * <p>Three keys under two prefixes, one of which is not ours:
 *
 * <ul>
 *   <li>{@link #ENABLED_KEY} — the horizontal master switch, shared with the engine's read path
 *       ({@link WorkloadAutoscalingProperties#HORIZONTAL_ENABLED_KEY}). While false the engine makes
 *       no HPA decision visible <em>and</em> no HPA apply is permitted.</li>
 *   <li>{@link #READINESS_KEY} — {@code xamops.k8s.automation.readiness.hpa-apply}, the platform-wide
 *       readiness map the CloudK8s UI also reads, already present in the base properties as false.
 *       Whether anything may be <em>applied</em>, independent of whether decisions are shown.</li>
 *   <li>{@link #TARGET_RETUNE_KEY} — the D3b standalone-retune sub-flag, off even in UAT except where
 *       deliberately turned on. Carried here so the apply path can refuse a standalone retune the
 *       engine would not emit.</li>
 * </ul>
 */
@Component
public class HorizontalAutoscalingProperties {

    /** Master switch, shared verbatim with {@link WorkloadAutoscalingProperties#HORIZONTAL_ENABLED_KEY}. */
    public static final String ENABLED_KEY = WorkloadAutoscalingProperties.HORIZONTAL_ENABLED_KEY;

    /**
     * Readiness for the {@code hpa-apply} control, shared with the UI's readiness map. False until
     * the HPA apply path's live validation has actually been performed against a cluster.
     */
    public static final String READINESS_KEY = "xamops.k8s.automation.readiness.hpa-apply";

    /**
     * The D3b standalone target-retune sub-flag. Off by default even when HPA automation is on
     * (owner decision Q4): a retune not driven by a vertical request move is a deliberate, separately
     * gated capability.
     */
    public static final String TARGET_RETUNE_KEY = "xamops.automation.horizontal.target-retune.enabled";

    private final boolean enabled;
    private final boolean ready;
    private final boolean executorPresent;
    private final boolean targetRetuneEnabled;

    /**
     * The production binding.
     *
     * <p>{@code executorPresent} is answered by asking the container whether an
     * {@link HpaApplyExecutor} bean exists. An {@link ObjectProvider} rather than a
     * constructor-injected bean so this stays resolvable when none is registered — and so no executor
     * can depend on this class without a cycle.
     */
    @Autowired
    public HorizontalAutoscalingProperties(
            @Value("${" + ENABLED_KEY + ":false}") boolean enabled,
            @Value("${" + READINESS_KEY + ":false}") boolean ready,
            @Value("${" + TARGET_RETUNE_KEY + ":false}") boolean targetRetuneEnabled,
            ObjectProvider<HpaApplyExecutor> executors) {
        this(enabled, ready, executors.stream().findAny().isPresent(), targetRetuneEnabled);
    }

    /** No executor, no retune: the shape every test that is not about those wants. */
    public HorizontalAutoscalingProperties(boolean enabled, boolean ready) {
        this(enabled, ready, false, false);
    }

    public HorizontalAutoscalingProperties(boolean enabled, boolean ready, boolean executorPresent) {
        this(enabled, ready, executorPresent, false);
    }

    public HorizontalAutoscalingProperties(boolean enabled, boolean ready, boolean executorPresent,
                                           boolean targetRetuneEnabled) {
        this.enabled = enabled;
        this.ready = ready;
        this.executorPresent = executorPresent;
        this.targetRetuneEnabled = targetRetuneEnabled;
    }

    /** Whether the HPA decisions are surfaced and the apply path may be used at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Whether the HPA apply path has passed live validation. Independent of {@link #isEnabled()}: the
     * decisions can be shown long before an apply is allowed.
     */
    public boolean isReady() {
        return ready;
    }

    /** Whether an executor exists that could carry an HPA apply at all. */
    public boolean isExecutorPresent() {
        return executorPresent;
    }

    /**
     * Whether the apply path is wired and permitted — enabled, ready, and an executor present. There
     * is <strong>no auto-apply tier for HPA in this task</strong>, so this gates the one-time,
     * user-initiated apply only; {@code autoApplyEligible} stays false at the mapper boundary.
     */
    public boolean isApplyPermitted() {
        return enabled && ready && executorPresent;
    }

    /** Whether a D3b standalone target retune may be applied. Off by default even in UAT. */
    public boolean isTargetRetuneEnabled() {
        return targetRetuneEnabled;
    }
}
