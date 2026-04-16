package com.xammer.billops.repository;

import com.xammer.billops.domain.UserPlatformConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPlatformConnectionRepository extends JpaRepository<UserPlatformConnection, Long> {

    Optional<UserPlatformConnection> findByUserIdAndPlatform(Long userId, String platform);

    List<UserPlatformConnection> findByUserId(Long userId);

    /** Used by inbound webhook handlers to resolve which XamOps user sent the reply */
    Optional<UserPlatformConnection> findByChannelIdAndPlatform(String channelId, String platform);

    /** Fallback: look up by Slack member ID / Teams user ID */
    Optional<UserPlatformConnection> findByPlatformUserIdAndPlatform(String platformUserId, String platform);
}
