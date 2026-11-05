package com.xammer.cloud.repository.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnvAllocationResultRepository extends JpaRepository<EnvAllocationResult, Long> {

    List<EnvAllocationResult> findByRunIdOrderByIdAsc(Long runId);
}
