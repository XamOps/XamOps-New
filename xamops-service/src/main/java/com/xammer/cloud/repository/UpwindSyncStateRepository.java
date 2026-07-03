package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UpwindSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UpwindSyncStateRepository extends JpaRepository<UpwindSyncState, Long> {

    Optional<UpwindSyncState> findByDomain(String domain);
}
