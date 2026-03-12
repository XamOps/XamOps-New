package com.xammer.cloud.repository;

import com.xammer.cloud.domain.StatefulAsgConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StatefulAsgConfigRepository extends JpaRepository<StatefulAsgConfig, Long> {

    Optional<StatefulAsgConfig> findByAccount_IdAndRegionAndAsgName(Long accountId, String region, String asgName);

    List<StatefulAsgConfig> findByAccount_IdAndStatefulEnabledTrue(Long accountId);
}
