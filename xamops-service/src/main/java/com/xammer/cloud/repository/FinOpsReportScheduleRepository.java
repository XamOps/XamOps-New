package com.xammer.cloud.repository;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface FinOpsReportScheduleRepository extends JpaRepository<FinOpsReportSchedule, Long> {

    List<FinOpsReportSchedule> findByCloudAccountId(Long cloudAccountId);

    @Query("SELECT s FROM FinOpsReportSchedule s " +
           "JOIN FETCH s.cloudAccount ca " +
           "JOIN FETCH ca.client " +
           "JOIN FETCH s.user " +
           "WHERE s.frequency = :frequency AND s.isActive = true")
    List<FinOpsReportSchedule> findAllActiveByFrequencyWithDetails(
            @Param("frequency") FinOpsReportSchedule.Frequency frequency);

    List<FinOpsReportSchedule> findByUserIdAndCloudAccountId(Long userId, Long cloudAccountId);

    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long cloudAccountId);

    @Query("SELECT DISTINCT s.cloudAccount FROM FinOpsReportSchedule s WHERE s.isActive = true")
    List<CloudAccount> findDistinctActiveAccounts();

    @Query("SELECT DISTINCT s.email FROM FinOpsReportSchedule s WHERE s.cloudAccount.id = :accountId AND s.isActive = true")
    List<String> findActiveEmailsByAccountId(@Param("accountId") Long accountId);

    /**
     * Mobile numbers for the same recipients {@link #findActiveEmailsByAccountId} returns.
     *
     * <p>A schedule row is keyed by email and has no phone column, so the number is reached
     * through the owning user. Rows whose user has no contact are filtered out in the query
     * rather than in Java so an account with no mobile-enabled recipients costs one empty result
     * set instead of loading every schedule.
     */
    @Query("SELECT DISTINCT s.user.contact FROM FinOpsReportSchedule s " +
           "WHERE s.cloudAccount.id = :accountId AND s.isActive = true " +
           "AND s.user.contact IS NOT NULL AND TRIM(s.user.contact) <> ''")
    List<String> findActiveContactsByAccountId(@Param("accountId") Long accountId);

    /**
     * The client owning a cloud account, for reading their notification-channel entitlement.
     *
     * <p>Fetched with an explicit query rather than {@code account.getClient()} because
     * {@code CloudAccount.client} is {@code FetchType.LAZY} and the alert and report senders run on
     * {@code @Scheduled} threads with no open session — traversing the association there throws
     * {@code LazyInitializationException}.
     */
    @Query("SELECT a.client FROM CloudAccount a WHERE a.id = :accountId")
    Optional<Client> findClientByAccountId(@Param("accountId") Long accountId);
}
