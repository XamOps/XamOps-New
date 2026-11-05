package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationRuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvAllocationRuleVersionRepository extends JpaRepository<EnvAllocationRuleVersion, Long> {

    List<EnvAllocationRuleVersion> findByClientIdOrderByVersionNumberDesc(Long clientId);

    Optional<EnvAllocationRuleVersion> findByClientIdAndVersionNumber(Long clientId, Integer versionNumber);

    Optional<EnvAllocationRuleVersion> findFirstByClientIdOrderByVersionNumberDesc(Long clientId);
}
