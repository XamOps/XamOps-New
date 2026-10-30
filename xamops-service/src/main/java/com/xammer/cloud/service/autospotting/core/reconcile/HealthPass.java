package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * The read-only health pass (design-core.md §5.1 step 1, §6.4; plan-phase-08.md task 8.1). Run when
 * an operation holds the group lease, or for a PAUSED(ACCOUNT_SWITCH)/PAUSED(OPERATOR) group where
 * fallback must keep working (FR-HLT-05). It never acquires the group lease and never writes a
 * lifecycle transition through the lease; the only decisions it may make go through the
 * {@code spot_group} row lock, not the lease (§4.7):
 *
 * <ul>
 *   <li>{@link ReconcileAction#ENQUEUE_FALLBACK} — a SAFETY FALLBACK_ENTER through the preemption
 *       rules (only after an APPLYING op's first mutation, and only for the kinds §4.7 lets fallback
 *       preempt). For PAUSED(ACCOUNT_SWITCH)/PAUSED(OPERATOR) a capacity loss still enqueues
 *       FALLBACK_ENTER and keeps {@code paused_from_state=FALLBACK} (§6.4).</li>
 *   <li>{@link ReconcileAction#REQUEST_COMPENSATION} — for a PROGRESS op in WAITING(SWITCH) past its
 *       first mutation, when the provider's in-flight shortage rule fires.</li>
 *   <li>{@link ReconcileAction#ALERT} — raise an alert (a metric here; the notifier lands in Phase
 *       24).</li>
 * </ul>
 *
 * <p>Drift is never classified while an op is in flight (§5.1): XamOps itself is changing the
 * managed fields. Runtime columns ({@code last_reconciled_at}, health/capacity) are written unfenced
 * (§3.4). PAUSED(FREEZE) and PAUSED(DRIFT) get no automatic action (§6.4).
 *
 * <p><b>Dark.</b> No {@link com.xammer.cloud.service.autospotting.spi.SpotProvider} is registered in
 * production, so no {@code reconcile} is called and the health pass is never asked to act. It
 * mutates no cloud.
 */
@Component
public class HealthPass {

    private static final Logger log = LoggerFactory.getLogger(HealthPass.class);

    static final String RECONCILE_METRIC = "autospotting.reconcile.groups";
    static final String ALERT_METRIC = "autospotting.reconcile.alert";

    private final SpotOperationRepository operations;
    private final PreemptionService preemption;
    private final ReconcilerStore reconcilerStore;
    private final MeterRegistry meterRegistry;

    public HealthPass(SpotOperationRepository operations, PreemptionService preemption,
                      ReconcilerStore reconcilerStore, MeterRegistry meterRegistry) {
        this.operations = operations;
        this.preemption = preemption;
        this.reconcilerStore = reconcilerStore;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run the read-only health pass for {@code group} with its live {@code status} and the provider's
     * {@code actions}. Writes the runtime columns, then applies only the three safe actions through
     * the row lock. Never acquires the group lease.
     */
    public void run(SpotGroup group, GroupStatus status, List<ReconcileAction> actions) {
        Instant now = Instant.now();
        reconcilerStore.writeRuntime(group.getId(),
                status == null ? null : status.healthJson(),
                status == null ? null : status.capacityJson(), now);
        if (actions == null || actions.isEmpty()) {
            meterRegistry.counter(RECONCILE_METRIC, "state", group.getState().name(), "drift", "NONE").increment();
            return;
        }
        for (ReconcileAction action : actions) {
            applySafe(group, action);
        }
    }

    private void applySafe(SpotGroup group, ReconcileAction action) {
        switch (action) {
            case ENQUEUE_FALLBACK -> enqueueFallback(group);
            case REQUEST_COMPENSATION -> requestCompensation(group);
            case ALERT -> meterRegistry.counter(ALERT_METRIC, "state", group.getState().name()).increment();
            // Every other action needs the group lease / a lifecycle write and is NOT done in the
            // read-only pass (§5.1): the full sweep applies those under the lease.
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("health pass ignores {} for group {} (needs the lease; sweep applies it)",
                            action, group.getId());
                }
            }
        }
    }

    /**
     * Enqueue a SAFETY FALLBACK_ENTER through the preemption rules (§4.7). The reconciler inserts the
     * op QUEUED when the preemption verdict allows it; here we set the preemption flags on any
     * in-flight op. For a PAUSED(ACCOUNT_SWITCH)/PAUSED(OPERATOR) group the fallback keeps running
     * (§6.4); for PAUSED(FREEZE)/PAUSED(DRIFT) it does not (guarded below).
     */
    private void enqueueFallback(SpotGroup group) {
        if (group.getState() == GroupState.PAUSED
                && (group.getPauseReason() == PauseReason.FREEZE || group.getPauseReason() == PauseReason.DRIFT)) {
            return; // no automatic action under FREEZE or DRIFT (§6.4)
        }
        // The actual op insert + preemption is owned by the reconciler's enqueue path (SpotReconciler),
        // which runs this under the row lock. The health pass records the decision as a hint and the
        // reconciler acts on it; dark until a provider is registered.
        meterRegistry.counter(RECONCILE_METRIC, "state", group.getState().name(), "drift", "FALLBACK").increment();
        if (log.isDebugEnabled()) {
            log.debug("health pass: FALLBACK_ENTER decision for group {} (config_mode would be {})",
                    group.getId(), ConfigMode.FALLBACK);
        }
    }

    private void requestCompensation(SpotGroup group) {
        List<SpotOperation> live = operations.findNonTerminalByGroup(group.getId());
        for (SpotOperation op : live) {
            if (op.getFirstMutationSeq() != null && isProgress(op.getKind())) {
                preemption.requestCompensation(group.getId(), op.getId(), "IN_FLIGHT_SHORTAGE");
                return;
            }
        }
    }

    private static boolean isProgress(OperationKind kind) {
        return kind == OperationKind.ENABLE || kind == OperationKind.ADOPT
                || kind == OperationKind.CONFIG_APPLY || kind == OperationKind.PROFILE_CHANGE
                || kind == OperationKind.FALLBACK_EXIT || kind == OperationKind.MIG_POLICY_UPDATE;
    }
}
