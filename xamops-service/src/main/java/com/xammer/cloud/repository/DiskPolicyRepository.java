package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DiskPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiskPolicyRepository extends JpaRepository<DiskPolicy, Long> {
    List<DiskPolicy> findByAccountId(Long accountId);
    Optional<DiskPolicy> findByAccountIdAndIsDefaultTrue(Long accountId);
}
