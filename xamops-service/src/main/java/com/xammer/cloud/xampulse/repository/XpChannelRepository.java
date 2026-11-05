package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpChannel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpChannelRepository extends JpaRepository<XpChannel, Long> {
    List<XpChannel> findByCloudAccountId(Long cloudAccountId);
    Optional<XpChannel> findByIdAndCloudAccountId(Long id, Long cloudAccountId);
    void deleteByIdAndCloudAccountId(Long id, Long cloudAccountId);
    Optional<XpChannel> findByIdAndCloudAccountIdIn(Long id, java.util.Collection<Long> cloudAccountIds);
    void deleteByIdAndCloudAccountIdIn(Long id, java.util.Collection<Long> cloudAccountIds);
}
