package com.xammer.cloud.repository;

import com.xammer.cloud.domain.LlmKeyGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LlmKeyGroupRepository extends JpaRepository<LlmKeyGroup, Long> {

    List<LlmKeyGroup> findByClientId(Long clientId);

    Optional<LlmKeyGroup> findByGroupKey(String groupKey);

    @Query("SELECT g FROM LlmKeyGroup g WHERE g.id = :id AND g.client.id = :clientId")
    Optional<LlmKeyGroup> findByIdAndClientId(Long id, Long clientId);

    /**
     * Drops a provider's membership rows from the key-group join table.
     *
     * <p>Needed before a provider config is deleted. {@code llm_key_group_provider} is owned by
     * {@link LlmKeyGroup#getProviders()}, so removing the config on its own leaves the join rows
     * pointing at a row that no longer exists and the foreign key rejects the delete at commit.
     *
     * <p>Native rather than JPQL because the join table has no entity of its own, and going
     * through the owning side would mean loading every group for the tenant just to mutate a set.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM llm_key_group_provider WHERE provider_id = :providerId",
           nativeQuery = true)
    int removeProviderFromAllGroups(@Param("providerId") Long providerId);
}
