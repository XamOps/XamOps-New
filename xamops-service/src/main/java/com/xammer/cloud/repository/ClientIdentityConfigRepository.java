package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClientIdentityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClientIdentityConfigRepository extends JpaRepository<ClientIdentityConfig, Long> {

    Optional<ClientIdentityConfig> findByClientId(Long clientId);

    boolean existsByClientId(Long clientId);
}
