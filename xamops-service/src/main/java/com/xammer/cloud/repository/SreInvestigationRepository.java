package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SreInvestigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SreInvestigationRepository extends JpaRepository<SreInvestigation, Long> {

    List<SreInvestigation> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<SreInvestigation> findByClientIdAndStatus(Long clientId, SreInvestigation.InvestigationStatus status);

    List<SreInvestigation> findByStatus(SreInvestigation.InvestigationStatus status);
}
