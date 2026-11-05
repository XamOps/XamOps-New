package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Finders for {@link AutoSpottingAllowlistEntry} (plan-phase-03.md task 3.2, plan-phase-05.md task
 * 5.1), keyed by {@code cloud_account_id}. Rows are added from B4; the operator API's writes arrive
 * with it.
 *
 * <p>{@link com.xammer.cloud.service.autospotting.core.switches.SwitchService} reads the allowed
 * account ids through {@link #findAllowedCloudAccountIds()}. An unreadable
 * {@code autospotting_allowlist} table makes that read throw, and the service treats it as the
 * empty allowlist (no account allowlisted), which is the fail-closed column of the §6.3 matrix.
 */
@Repository
public interface AutoSpottingAllowlistEntryRepository
        extends JpaRepository<AutoSpottingAllowlistEntry, Long> {

    List<AutoSpottingAllowlistEntry> findByProvider(
            com.xammer.cloud.domain.autospotting.Provider provider);

    /** The {@code cloud_account_id} of every allowlisted account. */
    @Query("select e.cloudAccountId from AutoSpottingAllowlistEntry e")
    List<Long> findAllowedCloudAccountIds();
}
