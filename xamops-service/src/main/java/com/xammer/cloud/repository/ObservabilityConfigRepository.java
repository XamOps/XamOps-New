package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ObservabilityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ObservabilityConfigRepository extends JpaRepository<ObservabilityConfig, Long> {

    Optional<ObservabilityConfig> findByClientId(Long clientId);

    boolean existsByClientId(Long clientId);
}
