package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClusterConnectToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data access for {@link ClusterConnectToken} (design §2.1, §2.2).
 */
@Repository
public interface ClusterConnectTokenRepository extends JpaRepository<ClusterConnectToken, Long> {

    Optional<ClusterConnectToken> findByTokenHash(String tokenHash);

    List<ClusterConnectToken> findByClusterConfigIdAndPurpose(Long clusterConfigId, String purpose);

    /**
     * Atomic single-use consume (design §2.2). Returns the number of rows updated:
     * 1 means this caller won the race and may proceed; 0 means the token was
     * already used, revoked, or expired and the caller must re-read the row to
     * pick the refusal code. Only the DB serialises concurrent exchanges (AC-4).
     */
    @Modifying
    @Query("UPDATE ClusterConnectToken t SET t.usedAt = :now, t.usedFromIp = :ip " +
           "WHERE t.id = :id AND t.usedAt IS NULL AND t.revokedAt IS NULL AND t.expiresAt > :now")
    int consume(@Param("id") Long id, @Param("now") Instant now, @Param("ip") String ip);

    /**
     * Revoke every still-live token for a (cluster, purpose) pair — the business
     * rule "at most one live token per (cluster, purpose)" (design §2.2),
     * applied in the same transaction as a new issue.
     */
    @Modifying
    @Query("UPDATE ClusterConnectToken t SET t.revokedAt = :now " +
           "WHERE t.clusterConfigId = :clusterConfigId AND t.purpose = :purpose " +
           "AND t.usedAt IS NULL AND t.revokedAt IS NULL")
    int revokeLiveTokens(@Param("clusterConfigId") Long clusterConfigId,
                         @Param("purpose") String purpose,
                         @Param("now") Instant now);
}
