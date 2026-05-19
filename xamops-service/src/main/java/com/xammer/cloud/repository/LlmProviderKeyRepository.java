package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmProviderKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LlmProviderKeyRepository extends JpaRepository<LlmProviderKey, Long> {

    List<LlmProviderKey> findByKeyGroupIdOrderByCreatedAtAsc(Long keyGroupId);

    List<LlmProviderKey> findByKeyGroupIdAndProviderAndStatusOrderByCreatedAtAsc(Long keyGroupId, String provider, String status);

    List<LlmProviderKey> findByKeyGroupIdAndProviderOrderByCreatedAtAsc(Long keyGroupId, String provider);

    Optional<LlmProviderKey> findByIdAndKeyGroupId(Long id, Long keyGroupId);

    long countByKeyGroupId(Long keyGroupId);

    @Modifying
    @Transactional
    @Query("UPDATE LlmProviderKey k SET k.currentRequests = k.currentRequests + 1, k.totalRequests = k.totalRequests + 1 WHERE k.id = :id")
    void incrementRequests(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE llm_provider_key SET current_requests = GREATEST(current_requests - 1, 0) WHERE id = :id", nativeQuery = true)
    void decrementRequests(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE LlmProviderKey k SET k.totalCost = k.totalCost + :cost, k.totalTokens = k.totalTokens + :tokens WHERE k.id = :id")
    void updateStats(@Param("id") Long id, @Param("cost") BigDecimal cost, @Param("tokens") long tokens);

    @Modifying
    @Transactional
    @Query("UPDATE LlmProviderKey k SET k.status = :status, k.rateLimitedUntil = :until WHERE k.id = :id")
    void updateStatusAndRateLimit(@Param("id") Long id, @Param("status") String status, @Param("until") LocalDateTime until);
}
