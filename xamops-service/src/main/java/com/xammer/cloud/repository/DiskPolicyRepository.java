package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DiskPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiskPolicyRepository extends JpaRepository<DiskPolicy, Long> {
    List<DiskPolicy> findByAccountId(Long accountId);
    Optional<DiskPolicy> findByAccountIdAndIsDefaultTrue(Long accountId);

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
