package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingAllowlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Finders for {@link AutoSpottingAllowlistEntry} (plan-phase-03.md task 3.2), keyed by
 * {@code cloud_account_id}. Rows are added from B4; the operator API's writes arrive with it.
 */
@Repository
public interface AutoSpottingAllowlistEntryRepository
        extends JpaRepository<AutoSpottingAllowlistEntry, Long> {

    List<AutoSpottingAllowlistEntry> findByProvider(
            com.xammer.cloud.domain.autospotting.Provider provider);
}
