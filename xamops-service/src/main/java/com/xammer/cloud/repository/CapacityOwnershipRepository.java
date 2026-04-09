package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CapacityOwnership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CapacityOwnershipRepository extends JpaRepository<CapacityOwnership, Long> {

    Optional<CapacityOwnership> findByCidrId(String cidrId);
}
