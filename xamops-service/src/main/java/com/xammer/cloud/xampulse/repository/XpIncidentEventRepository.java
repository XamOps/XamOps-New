package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpIncidentEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpIncidentEventRepository extends JpaRepository<XpIncidentEvent, Long> {
    List<XpIncidentEvent> findByIncidentIdOrderByTsAsc(Long incidentId);
}
