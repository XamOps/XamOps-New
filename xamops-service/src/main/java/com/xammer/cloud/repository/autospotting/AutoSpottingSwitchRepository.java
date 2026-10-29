package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.AutoSpottingSwitch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Finders for {@link AutoSpottingSwitch} (plan-phase-03.md task 3.2), keyed by the scope key. The
 * operator API's writes and the cached reads arrive with {@code SwitchService} in Phase 05.
 */
@Repository
public interface AutoSpottingSwitchRepository extends JpaRepository<AutoSpottingSwitch, String> {
}
