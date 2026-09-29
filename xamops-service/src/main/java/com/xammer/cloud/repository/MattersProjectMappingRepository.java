package com.xammer.cloud.repository;

import com.xammer.cloud.domain.MattersProjectMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MattersProjectMappingRepository extends JpaRepository<MattersProjectMapping, Long> {

    /**
     * The one mapping for a client — the lookup every read goes through.
     *
     * Reads resolve the caller's {@code clientId} from the authenticated principal and land here.
     * Because the resolved key is itself scoped to one Matters project, this single lookup is the
     * whole tenancy boundary for the add-on; nothing downstream can widen it.
     */
    Optional<MattersProjectMapping> findByClientId(Long clientId);

    /**
     * Guards the "one project, one customer" rule at the service layer so a duplicate is rejected
     * with a clear message rather than surfacing as a constraint-violation stack trace.
     * The unique index remains the real enforcement.
     */
    Optional<MattersProjectMapping> findByMattersProjectId(String mattersProjectId);

    boolean existsByClientId(Long clientId);

    /**
     * Every mapping with its client eagerly joined, for the admin list.
     *
     * The fetch join is deliberate: {@code client} is LAZY, and the admin screen renders the
     * customer name for every row, so the default mapping would issue one query per mapping.
     */
    @Query("SELECT m FROM MattersProjectMapping m JOIN FETCH m.client ORDER BY m.updatedAt DESC")
    List<MattersProjectMapping> findAllWithClient();
}
