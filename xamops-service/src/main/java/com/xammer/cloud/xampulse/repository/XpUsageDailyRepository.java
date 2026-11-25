package com.xammer.cloud.xampulse.repository;

import com.xammer.cloud.xampulse.domain.XpUsageDaily;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every finder on a tenant entity carries cloudAccountId (see XamPulseRepositoryScopingTest). */
public interface XpUsageDailyRepository extends JpaRepository<XpUsageDaily, Long> {
    List<XpUsageDaily> findByCloudAccountIdAndDayGreaterThanEqual(Long cloudAccountId, LocalDate from);

    Optional<XpUsageDaily> findByCloudAccountIdAndDayAndSignalAndSource(Long cloudAccountId, LocalDate day,
                                                                        String signal, String source);
}
