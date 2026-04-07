package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkspaceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkspaceConnectionRepository extends JpaRepository<WorkspaceConnection, Long> {

    Optional<WorkspaceConnection> findByClientId(Long clientId);

    boolean existsByClientId(Long clientId);

    void deleteByClientId(Long clientId);
}
