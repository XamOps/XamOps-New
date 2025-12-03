package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ArchitectureBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchitectureBlueprintRepository extends JpaRepository<ArchitectureBlueprint, Long> {
    // Standard CRUD methods are automatically provided
}