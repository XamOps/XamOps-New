package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CicdNotificationConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CicdNotificationConfigRepository extends JpaRepository<CicdNotificationConfig, Long> {
    Optional<CicdNotificationConfig> findByUserId(Long userId);
}
