package com.xammer.cloud.service.autospotting.core.lease;

import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a unit of lifecycle work inside a transaction whose <em>first</em> statement is the fence
 * check (design-core.md §3.4). Every lifecycle write to {@code spot_group}, every
 * {@code spot_operation}/{@code spot_operation_step} write by a runner and every transition insert
 * goes through here.
 *
 * <p>If the fence row no longer matches the handle's token and generation (another holder took the
 * lease, or it expired), the method throws {@link LeaseLostException} and the transaction rolls back
 * with nothing committed (AC-SPN-03). A store outage reaching the fence maps to
 * {@link LeaseUnavailableException} and counts {@code autospotting.lease.failures{reason=store_error}}
 * (AC-SPN-02), consistent with {@link LeaseService}.
 *
 * <p>Only GROUP handles are fenced here: group leases guard {@code spot_group} and its operation and
 * transition writes. Job-lease fencing on the ledger path lives with the ledger (design-core.md
 * §3.4), not in this phase.
 */
@Component
public class FencedTx {

    private final LeaseRepository leases;
    private final AutoSpottingMetrics metrics;

    public FencedTx(LeaseRepository leases, AutoSpottingMetrics metrics) {
        this.leases = leases;
        this.metrics = metrics;
    }

    /** Fenced work that returns nothing. */
    @FunctionalInterface
    public interface Work {
        void run();
    }

    /** Runs {@code work} fenced on {@code handle}; nothing commits unless the lease is still held. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void run(LeaseHandle handle, Work work) {
        fenced(handle, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} fenced on {@code handle} and returns its result. The fence is the first
     * statement of the transaction, so a lost lease prevents every later write.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> T call(LeaseHandle handle, Supplier<T> work) {
        return fenced(handle, work);
    }

    private <T> T fenced(LeaseHandle handle, Supplier<T> work) {
        if (handle.kind() != LeaseHandle.Kind.GROUP) {
            throw new IllegalArgumentException("FencedTx fences group leases; got " + handle.kind());
        }
        boolean held;
        try {
            held = leases.fenceHeld(UUID.fromString(handle.resourceId()), handle.token(), handle.generation());
        } catch (DataAccessException | TransactionException e) {
            metrics.leaseFailure("store_error");
            throw new LeaseUnavailableException("lease store unreachable during fence check", e);
        }
        if (!held) {
            handle.markLost();
            throw new LeaseLostException("fence failed for group " + handle.resourceId()
                    + " (token=" + handle.token() + ", gen=" + handle.generation() + ")");
        }
        return work.get();
    }
}
