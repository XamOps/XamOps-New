package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpSilence;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpSilenceRepository extends JpaRepository<XpSilence, Long> {
    List<XpSilence> findByCloudAccountId(Long cloudAccountId);
    List<XpSilence> findByCloudAccountIdAndStartsAtBeforeAndEndsAtAfter(Long cloudAccountId, Instant startsBefore, Instant endsAfter);
    Optional<XpSilence> findByIdAndCloudAccountId(Long id, Long cloudAccountId);
    Optional<XpSilence> findByIdAndCloudAccountIdIn(Long id, java.util.Collection<Long> cloudAccountIds);
}
