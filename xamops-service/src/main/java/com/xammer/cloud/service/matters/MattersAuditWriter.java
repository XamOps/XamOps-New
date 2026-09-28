package com.xammer.cloud.service.matters;

import com.xammer.cloud.domain.MattersAccessAudit;
import com.xammer.cloud.repository.MattersAccessAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit rows. Exists as a separate bean purely so {@link Async} actually takes effect.
 *
 * <p>Spring implements {@code @Async} with a proxy, and a proxy can only intercept calls that arrive
 * from outside the bean. Had this method stayed on {@link MattersAuditService} — where it is called
 * from {@code recordCredentialUse} — the annotation would have been silently ignored and every audit
 * insert would have run inline on the request thread. That is the failure mode this split exists to
 * prevent: not a compile error, not a warning, just the asynchrony quietly not happening and a
 * database round trip appearing in front of every DSPM page load.
 *
 * <p>{@link Propagation#REQUIRES_NEW} on both methods. Without it an audit insert that failed could
 * mark an enclosing transaction rollback-only and take the read down with it — the audit trail must
 * never be able to break the thing it is auditing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MattersAuditWriter {

    private final MattersAccessAuditRepository auditRepository;

    /**
     * Off-thread write, for the high-volume credential-use rows.
     *
     * A failure is logged and dropped. The trade is deliberate: under a hard crash a few queued rows
     * can be lost, which is acceptable for a record of read activity and is much better than letting
     * an audit problem become a customer-facing outage.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAsync(MattersAccessAudit row) {
        try {
            auditRepository.save(row);
        } catch (Exception e) {
            log.warn("[Matters] audit write failed action={} client={}: {}",
                    row.getAction(), row.getClientId(), e.getMessage());
        }
    }

    /**
     * On-thread write, for operator mutations.
     *
     * Synchronous because these are the rows with lasting value — who rotated a key, who repointed a
     * project, who granted the add-on — and they are rare enough that the latency is irrelevant.
     * Logged at error rather than warn on failure, since a mutation that cannot be audited is a real
     * problem, but still never rethrown: the mutation itself has already been applied and failing the
     * request now would leave the caller believing it did not happen.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeSync(MattersAccessAudit row) {
        try {
            auditRepository.save(row);
        } catch (Exception e) {
            log.error("[Matters] failed to write audit row action={} client={}: {}",
                    row.getAction(), row.getClientId(), e.getMessage());
        }
    }
}
