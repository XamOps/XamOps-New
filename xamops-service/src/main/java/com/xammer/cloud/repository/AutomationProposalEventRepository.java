package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AutomationProposalEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AutomationProposalEventRepository extends JpaRepository<AutomationProposalEvent, Long> {

    /** Events of one proposal, scoped to its cluster, oldest first. */
    List<AutomationProposalEvent> findByProposalIdAndClusterConfigIdOrderByAtAscIdAsc(String proposalId,
                                                                                      Long clusterConfigId);

    @Modifying
    @Query("delete from AutomationProposalEvent e where e.at < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
