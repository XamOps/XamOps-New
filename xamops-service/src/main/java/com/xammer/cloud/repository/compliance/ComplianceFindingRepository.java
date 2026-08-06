package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceFindingRepository extends JpaRepository<ComplianceFinding, Long> {

    List<ComplianceFinding> findByScanId(Long scanId);

    List<ComplianceFinding> findByScanIdAndCheckId(Long scanId, String checkId);

    void deleteByScanId(Long scanId);
}
