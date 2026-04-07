package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbAgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DbAgentConfigRepository extends JpaRepository<DbAgentConfig, Long> {

    Optional<DbAgentConfig> findByCloudAccountIdAndAgentName(Long cloudAccountId, String agentName);

    List<DbAgentConfig> findByCloudAccountId(Long cloudAccountId);

    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long cloudAccountId);
}
