package com.xammer.cloud.repository;

import com.xammer.cloud.domain.JenkinsIntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface JenkinsIntegrationConfigRepository extends JpaRepository<JenkinsIntegrationConfig, Long> {
    List<JenkinsIntegrationConfig> findByUserId(Long userId);
}