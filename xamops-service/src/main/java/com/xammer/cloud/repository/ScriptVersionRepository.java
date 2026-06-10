package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ScriptVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link ScriptVersion}.
 *
 * Requirements: REQ-2.7.1 (Script Versioning)
 */
@Repository
public interface ScriptVersionRepository extends JpaRepository<ScriptVersion, Long> {

    /**
     * Version history for a script, newest release first.
     */
    List<ScriptVersion> findByScriptIdOrderByReleasedAtDesc(Long scriptId);

    /**
     * A specific version of a script, if it exists.
     */
    Optional<ScriptVersion> findByScriptIdAndVersion(Long scriptId, String version);
}
