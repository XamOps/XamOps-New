package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.JobLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Finders for {@link JobLease} (plan-phase-03.md task 3.2), keyed by the job name. The
 * acquire/renew/release guarded native UPDATEs arrive with {@code LeaseService} in Phase 04.
 */
@Repository
public interface JobLeaseRepository extends JpaRepository<JobLease, String> {
}
