package com.xammer.cloud.repository.autospotting;

import com.xammer.cloud.domain.autospotting.SpotGroupSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Finders for {@link SpotGroupSnapshot} (plan-phase-03.md task 3.2). Rows are append-only; the
 * database trigger rejects any UPDATE or DELETE.
 */
@Repository
public interface SpotGroupSnapshotRepository extends JpaRepository<SpotGroupSnapshot, UUID> {

    List<SpotGroupSnapshot> findByGroupIdOrderByCapturedAtDesc(UUID groupId);
}
