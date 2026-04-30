package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SloDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SloDefinitionRepository extends JpaRepository<SloDefinition, Long> {
    List<SloDefinition> findByEnabledTrue();
}
