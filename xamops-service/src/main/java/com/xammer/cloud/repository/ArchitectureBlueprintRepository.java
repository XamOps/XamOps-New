package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ArchitectureBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Blueprint reads are tenant-scoped.
 *
 * <p>
 * The unscoped {@code findAll()} and {@code findById()} that {@code JpaRepository}
 * supplies are what leaked every tenant's architectures through
 * {@code /api/xamops/design}. They still exist on the interface, so the rule is:
 * <strong>callers must use the {@code ...ClientId} variants below.</strong>
 * Nothing outside a superadmin path has a legitimate reason to read a blueprint
 * without a client id.
 */
@Repository
public interface ArchitectureBlueprintRepository extends JpaRepository<ArchitectureBlueprint, Long> {

    /** Every blueprint owned by one tenant, newest edit first. */
    List<ArchitectureBlueprint> findByClientIdOrderByUpdatedAtDesc(Long clientId);

    /**
     * One blueprint, but only if it belongs to this tenant.
     *
     * <p>
     * Returning empty for a foreign id is deliberate: the caller renders a 404, so
     * the response cannot be used to probe which blueprint ids exist in other
     * tenants.
     */
    Optional<ArchitectureBlueprint> findByIdAndClientId(Long id, Long clientId);
}
