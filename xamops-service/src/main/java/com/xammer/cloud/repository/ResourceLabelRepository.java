package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ResourceLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceLabelRepository extends JpaRepository<ResourceLabel, Long> {

    List<ResourceLabel> findByClientIdOrderByNameAsc(Long clientId);

    Optional<ResourceLabel> findByClientIdAndName(Long clientId, String name);

    boolean existsByClientIdAndName(Long clientId, String name);

    Optional<ResourceLabel> findByIdAndClientId(Long id, Long clientId);
}
