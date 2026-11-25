package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpIncident;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpIncidentRepository extends JpaRepository<XpIncident, Long> {
    Page<XpIncident> findByCloudAccountIdIn(Collection<Long> cloudAccountIds, Pageable pageable);
    Page<XpIncident> findByCloudAccountIdInAndStateIn(Collection<Long> cloudAccountIds, Collection<String> states, Pageable pageable);
    List<XpIncident> findByCloudAccountIdAndStateIn(Long cloudAccountId, Collection<String> states);
    Optional<XpIncident> findByIdAndCloudAccountId(Long id, Long cloudAccountId);
    long countByCloudAccountIdAndStateIn(Long cloudAccountId, Collection<String> states);
    Optional<XpIncident> findByIdAndCloudAccountIdIn(Long id, Collection<Long> cloudAccountIds);
}
