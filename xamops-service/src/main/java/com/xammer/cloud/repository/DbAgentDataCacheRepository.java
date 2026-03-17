package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbAgentDataCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DbAgentDataCacheRepository extends JpaRepository<DbAgentDataCache, Long> {

    Optional<DbAgentDataCache> findByDbAgentIdAndDataType(Long dbAgentId, String dataType);

    List<DbAgentDataCache> findByDbAgentId(Long dbAgentId);
}
