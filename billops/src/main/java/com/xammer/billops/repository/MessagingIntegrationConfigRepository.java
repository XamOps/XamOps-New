package com.xammer.billops.repository;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface MessagingIntegrationConfigRepository extends JpaRepository<MessagingIntegrationConfig, Long> {

    Optional<MessagingIntegrationConfig> findByPlatform(String platform);

    List<MessagingIntegrationConfig> findByEnabledTrue();
}
