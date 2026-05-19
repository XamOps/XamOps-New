package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmKeyGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LlmKeyGroupRepository extends JpaRepository<LlmKeyGroup, Long> {

    List<LlmKeyGroup> findByClientId(Long clientId);

    Optional<LlmKeyGroup> findByGroupKey(String groupKey);

    @Query("SELECT g FROM LlmKeyGroup g WHERE g.id = :id AND g.client.id = :clientId")
    Optional<LlmKeyGroup> findByIdAndClientId(Long id, Long clientId);
}
