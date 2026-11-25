package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpLogSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpLogSourceRepository extends JpaRepository<XpLogSource, Long> {
    List<XpLogSource> findByCloudAccountId(Long cloudAccountId);
    void deleteByCloudAccountId(Long cloudAccountId);
}
