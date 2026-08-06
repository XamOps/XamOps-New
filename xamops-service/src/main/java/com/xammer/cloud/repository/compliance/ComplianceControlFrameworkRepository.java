package com.xammer.cloud.repository.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlFramework;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceControlFrameworkRepository extends JpaRepository<ComplianceControlFramework, Long> {

    List<ComplianceControlFramework> findByScanId(Long scanId);

    void deleteByScanId(Long scanId);
}
