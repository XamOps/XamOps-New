package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one set of revert preconditions (design-core.md §7.4 "Revert preconditions, one set", DR-03,
 * FR-REV-05, PD-07; plan-phase-11.md task 11.2). Plan creation, confirm and every REVERT program's
 * step 0 use this and nothing else. It is deliberately narrow — the §7.4 list:
 *
 * <ul>
 *   <li>switches: only the freeze (e) {@code AS_FROZEN}, an unreadable switch store
 *       {@code AS_SWITCH_UNREADABLE} (PD-07, C-19; 503, retryable — a revert is held until the store
 *       can be read), and (p) {@code autospotting.{aws,gcp}.v2-mutations-enabled} before B5/B6;</li>
 *   <li>authorization, kind/state, plan version/expiry, acknowledgments, busy/preemption and the
 *       live fingerprint — each performed by the shared confirm checks, not re-done here;</li>
 *   <li>the provider's own restore-permission pre-check, which never blocks the plan: a missing
 *       permission adds {@code REVERT_NEEDS_PERMISSION} and an {@code EXACT_RESTORE_IMPOSSIBLE}
 *       alternative to the plan instead.</li>
 * </ul>
 *
 * <p>It never checks eligibility, profile, 100%-Spot or desired size — a revert at desired 1, or a
 * group that gained a new blocker since enable, can always be reverted (§7.4).
 */
@Component
public class RevertPreconditions {

    public static final String AS_FROZEN = "AS_FROZEN";
    public static final String AS_SWITCH_UNREADABLE = "AS_SWITCH_UNREADABLE";

    private final AutoSpottingSwitchProperties switchProperties;

    public RevertPreconditions(AutoSpottingSwitchProperties switchProperties) {
        this.switchProperties = switchProperties;
    }

    /**
     * The switch checks that gate a revert (§7.4). Throws {@link AutoSpottingApiException} when a
     * revert must be held:
     * <ul>
     *   <li>503 {@code AS_SWITCH_UNREADABLE} (retryable) when the switch store can't be read — the
     *       fail-closed interpretation of PD-07/C-19, held until it can;</li>
     *   <li>409 {@code AS_FROZEN} when the emergency freeze (e) is engaged.</li>
     * </ul>
     *
     * <p>The per-cloud (p) switch does <b>not</b> gate a revert: before B5/B6 the v2 restore program
     * is dark, but a revert plan is still computed and shown (no mutation runs this phase). The (p)
     * gate applies to cloud mutation, which this phase does not enable.
     */
    public void checkSwitches(SwitchState switchState) {
        if (switchState.switchStoreUnreadable()) {
            throw new AutoSpottingApiException(HttpStatus.SERVICE_UNAVAILABLE, AS_SWITCH_UNREADABLE,
                    "AutoSpotting can't read its safety switches right now; revert is held until it can.",
                    true, null, null);
        }
        if (switchState.freezeEngaged()) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, AS_FROZEN,
                    "AutoSpotting is frozen; revert is held until the freeze is released.");
        }
    }

    /** True when the per-cloud v2 restore program is live (B5 for AWS, B6 for GCP), for the step-0 note. */
    public boolean v2RestoreLive(Provider provider) {
        return switchProperties.v2MutationsEnabled(provider == Provider.AWS);
    }
}
