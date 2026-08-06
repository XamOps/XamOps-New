package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceRemediation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceRemediationRepository extends JpaRepository<ComplianceRemediation, Long> {

    List<ComplianceRemediation> findByCloudAccountId(String cloudAccountId);

    List<ComplianceRemediation> findByCloudAccountIdAndCheckId(String cloudAccountId, String checkId);

    /**
     * Work that claims to be done but has not yet been seen passing by a scan. These are
     * the rows the verification sweep closes out.
     */
    @Query("SELECT r FROM ComplianceRemediation r "
            + "WHERE r.cloudAccountId = :accountId AND r.status = 'RESOLVED' "
            + "AND r.verifiedAt IS NULL")
    List<ComplianceRemediation> findAwaitingVerification(@Param("accountId") String accountId);

    @Query("SELECT r FROM ComplianceRemediation r "
            + "WHERE r.cloudAccountId = :accountId AND r.status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')")
    List<ComplianceRemediation> findOpen(@Param("accountId") String accountId);
}
