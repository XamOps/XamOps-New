package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpMigrationLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpMigrationLogRepository extends JpaRepository<XpMigrationLog, Long> {
    Optional<XpMigrationLog> findByVersion(String version);
}
