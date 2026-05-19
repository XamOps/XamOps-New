package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmMasterKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LlmMasterKeyRepository extends JpaRepository<LlmMasterKey, Long> {

    Optional<LlmMasterKey> findByClientId(Long clientId);

    Optional<LlmMasterKey> findByMasterKey(String masterKey);
}
