package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudsitterPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CloudsitterPolicyRepository extends JpaRepository<CloudsitterPolicy, Long> {
    // Standard CRUD operations are automatically provided
}