package com.xammer.cloud.service.automation.proposal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a minute: expire PENDING and unclaimed APPROVED rows, fail DISPATCHED rows past their lease
 * (only when the agent has also gone silent), mark rows the source stopped re-sending STALE, and prune
 * history. All of it is {@link ProposalService#sweep()}; this class only owns the schedule so a
 * failure never kills the scheduler thread.
 */
@Component
public class ProposalSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProposalSweeper.class);

    private final ProposalService proposalService;

    public ProposalSweeper(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        try {
            ProposalService.SweepResult r = proposalService.sweep();
            if (r.expiredPending() + r.expiredApproved() + r.failedDispatched() + r.staleNotReseen() + r.deleted() > 0) {
                log.info("Proposal sweep: {} pending expired, {} approved expired, {} dispatched failed, "
                                + "{} stale (not re-seen), {} deleted",
                        r.expiredPending(), r.expiredApproved(), r.failedDispatched(), r.staleNotReseen(), r.deleted());
            }
        } catch (RuntimeException e) {
            log.warn("Proposal sweep failed: {}", e.getMessage());
        }
    }
}
