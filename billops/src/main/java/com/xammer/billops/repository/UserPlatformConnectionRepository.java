package com.xammer.billops.repository;

import com.xammer.billops.domain.UserPlatformConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPlatformConnectionRepository extends JpaRepository<UserPlatformConnection, Long> {

    Optional<UserPlatformConnection> findByUserIdAndPlatform(Long userId, String platform);

    List<UserPlatformConnection> findByUserId(Long userId);

    /** Removes all messaging-platform links (Slack/Teams/GChat) for this user on deletion. */
    @Modifying
    @Transactional
    @Query("DELETE FROM UserPlatformConnection c WHERE c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** Used by inbound webhook handlers to resolve which XamOps user sent the reply */
    Optional<UserPlatformConnection> findByChannelIdAndPlatform(String channelId, String platform);

    /** Fallback: look up by Slack member ID / Teams user ID */
    Optional<UserPlatformConnection> findByPlatformUserIdAndPlatform(String platformUserId, String platform);

    /** Used by polling scheduler to get all active connections for a platform */
    List<UserPlatformConnection> findAllByPlatformAndActive(String platform, boolean active);
}
