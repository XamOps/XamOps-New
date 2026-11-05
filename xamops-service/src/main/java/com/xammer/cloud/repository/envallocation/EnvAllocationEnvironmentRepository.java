package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvAllocationEnvironmentRepository extends JpaRepository<EnvAllocationEnvironment, Long> {

    List<EnvAllocationEnvironment> findByClientIdOrderBySortOrderAscIdAsc(Long clientId);

    Optional<EnvAllocationEnvironment> findByIdAndClientId(Long id, Long clientId);

    long countByClientId(Long clientId);
}
