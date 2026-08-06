package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlOwner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceControlOwnerRepository extends JpaRepository<ComplianceControlOwner, Long> {

    List<ComplianceControlOwner> findByCloudAccountId(String cloudAccountId);

    Optional<ComplianceControlOwner> findByCloudAccountIdAndCheckId(String cloudAccountId, String checkId);
}
