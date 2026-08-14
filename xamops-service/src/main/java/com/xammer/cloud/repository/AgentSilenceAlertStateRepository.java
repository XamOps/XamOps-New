package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentSilenceAlertState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSilenceAlertStateRepository extends JpaRepository<AgentSilenceAlertState, Long> {

    /** Currently-open alerts for a client — one lookup per scan rather than per host. */
    List<AgentSilenceAlertState> findByClientIdAndResolvedAtIsNull(Long clientId);
}
