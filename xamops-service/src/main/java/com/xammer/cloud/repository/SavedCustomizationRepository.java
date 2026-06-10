package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SavedCustomization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedCustomizationRepository extends JpaRepository<SavedCustomization, Long> {

    /**
     * All presets saved by a user across every script (newest first).
     */
    List<SavedCustomization> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * Presets a user saved for a single script.
     */
    List<SavedCustomization> findByUserIdAndScriptId(String userId, Long scriptId);

    /**
     * The user's current default preset for a script, if one exists. Used to
     * enforce the "only one default per user+script" invariant.
     */
    Optional<SavedCustomization> findByUserIdAndScriptIdAndIsDefaultTrue(String userId, Long scriptId);

    /**
     * Count presets for a user+script (used to enforce the max-presets limit).
     */
    long countByUserIdAndScriptId(String userId, Long scriptId);
}
