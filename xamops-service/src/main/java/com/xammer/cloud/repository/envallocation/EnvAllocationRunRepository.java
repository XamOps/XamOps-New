package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EnvAllocationRunRepository extends JpaRepository<EnvAllocationRun, Long> {

    Optional<EnvAllocationRun> findByIdAndClientId(Long id, Long clientId);

    /** Every run for one account, newest first — the run history list. */
    List<EnvAllocationRun> findTop100ByClientIdAndAwsAccountIdOrderByQueuedAtDesc(Long clientId, String awsAccountId);

    /** Every run for one month of one account, newest first. */
    List<EnvAllocationRun> findByClientIdAndAwsAccountIdAndBillingPeriodOrderByQueuedAtDesc(
            Long clientId, String awsAccountId, String billingPeriod);

    boolean existsByClientIdAndAwsAccountIdAndBillingPeriodAndCostBasisAndStatusIn(
            Long clientId, String awsAccountId, String billingPeriod, String costBasis,
            Collection<String> statuses);

    boolean existsByClientIdAndAwsAccountIdAndBillingPeriodAndCostBasisAndLockedTrue(
            Long clientId, String awsAccountId, String billingPeriod, String costBasis);

    /** Runs left QUEUED or RUNNING by an instance that stopped before finishing them. */
    List<EnvAllocationRun> findByStatusInAndQueuedAtBefore(Collection<String> statuses, LocalDateTime cutoff);
}
