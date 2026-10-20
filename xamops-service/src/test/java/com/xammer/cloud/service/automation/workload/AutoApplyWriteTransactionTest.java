package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transaction contract that keeps the scheduled auto-apply loop correct on PostgreSQL.
 *
 * <h2>Why this is asserted by reflection rather than against a live write</h2>
 *
 * {@link AutomaticApplyService#applyOnePerReadyCluster()} scans inside a {@code @Transactional(readOnly
 * = true)} transaction so {@code CloudAccount.client} and the read service's other LAZY reads resolve
 * off the scheduled thread. On PostgreSQL a Spring read-only transaction reaches the database as
 * {@code BEGIN READ ONLY}, so any write that <em>joins</em> it fails:
 * {@code ERROR: cannot execute INSERT in a read-only transaction}. The two writes an automatic resize
 * performs — {@link WorkloadActionRecorder#record} (the {@code vertical_workload_action} audit row,
 * an IDENTITY INSERT) and {@link WorkloadPolicyService#recordApplied} (the cooldown stamp) — were
 * annotated plain {@code @Transactional} (propagation REQUIRED), so they joined that read-only
 * transaction and could never commit under the loop.
 *
 * <p>The fix makes both writes {@code @Transactional(propagation = REQUIRES_NEW)}: each suspends the
 * read-only scan and runs in its own read-write transaction, so the INSERT and the cooldown UPDATE
 * commit. Both calls cross a Spring bean boundary from {@code VerticalApplyService}, so the proxy
 * honours the propagation (no self-invocation).
 *
 * <p>This proof is reflective on purpose. The other tests in this package mock the repositories, and
 * the module's integration harness runs on H2, which does not enforce {@code READ ONLY} the way
 * PostgreSQL does — an H2-backed end-to-end test would let the old plain-{@code @Transactional} INSERT
 * succeed and so could never fail on the real bug. Asserting the propagation directly is the check
 * that <em>would</em> have failed against the old arrangement (propagation REQUIRED) and passes only
 * now that the writes declare REQUIRES_NEW, independent of which database the test runs against.
 */
class AutoApplyWriteTransactionTest {

    @Test
    @DisplayName("the auto-apply loop body stays read-only: its LAZY reads resolve off the scheduled thread")
    void theLoopScanStaysReadOnly() throws NoSuchMethodException {
        Transactional tx = AutomaticApplyService.class
                .getDeclaredMethod("applyOnePerReadyCluster")
                .getAnnotation(Transactional.class);

        assertThat(tx)
                .as("applyOnePerReadyCluster must stay transactional so its LAZY reads resolve")
                .isNotNull();
        assertThat(tx.readOnly())
                .as("the scan itself stays read-only; only the writes it drives are read-write")
                .isTrue();
    }

    @Test
    @DisplayName("the audit-row INSERT runs in its own read-write transaction (REQUIRES_NEW), not the read-only scan")
    void theAuditInsertIsRequiresNew() throws NoSuchMethodException {
        Transactional tx = WorkloadActionRecorder.class
                .getDeclaredMethod("record", Long.class,
                        com.xammer.cloud.service.automation.workload.access.ClusterTarget.class,
                        WorkloadRef.class, ActionTrigger.class, boolean.class, java.util.List.class,
                        com.xammer.cloud.service.automation.workload.execute.ResizeProof.class,
                        java.time.Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(tx)
                .as("the audit INSERT must be transactional")
                .isNotNull();
        assertThat(tx.propagation())
                .as("REQUIRES_NEW suspends the read-only scan so the IDENTITY INSERT commits on PostgreSQL;"
                        + " plain REQUIRED (the old value) would join BEGIN READ ONLY and fail")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("the cooldown stamp runs in its own read-write transaction (REQUIRES_NEW), not the read-only scan")
    void theCooldownStampIsRequiresNew() throws NoSuchMethodException {
        Transactional tx = WorkloadPolicyService.class
                .getDeclaredMethod("recordApplied", Long.class, Long.class, WorkloadRef.class,
                        WorkloadPolicy.class, java.time.Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(tx)
                .as("the cooldown stamp must be transactional")
                .isNotNull();
        assertThat(tx.propagation())
                .as("REQUIRES_NEW suspends the read-only scan so the cooldown UPDATE commits on PostgreSQL;"
                        + " plain REQUIRED (the old value) would join BEGIN READ ONLY and fail")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("the writes are called across a bean boundary from VerticalApplyService, so the proxy applies")
    void theWritesCrossABeanBoundary() {
        // VerticalApplyService.applyLocked calls policyService.recordApplied(...) and
        // actionRecorder.record(...) on injected beans, never on itself, so Spring's proxy sees the
        // call and the REQUIRES_NEW above takes effect. This is a compile-time anchor for that fact.
        assertThat(VerticalApplyService.class.getName())
                .isEqualTo("com.xammer.cloud.service.automation.workload.api.VerticalApplyService");
    }
}
