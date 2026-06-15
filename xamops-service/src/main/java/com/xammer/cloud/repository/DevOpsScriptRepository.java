package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DevOpsScript;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DevOpsScriptRepository extends JpaRepository<DevOpsScript, Long> {
    // Spring Data JPA auto-generates find, save, delete, etc.

    /**
     * All scripts with the given lifecycle status (e.g. "published").
     */
    List<DevOpsScript> findByStatus(String status);

    /**
     * Paginated scripts filtered by lifecycle status.
     */
    Page<DevOpsScript> findByStatus(String status, Pageable pageable);

    /**
     * Look up a single script by its unique, human-friendly name. Used by the
     * bulk-import seed loader (Task 4.6) for idempotency (skip already-imported
     * scripts) and to match the Terraform parameter-only manifest to the rows
     * inserted by terraform_scripts_seed.sql.
     */
    Optional<DevOpsScript> findFirstByName(String name);

    /**
     * Whether a script with the given name already exists. Used to guard the
     * one-time Terraform SQL seed and skip duplicate imports on every startup.
     */
    boolean existsByName(String name);
}
