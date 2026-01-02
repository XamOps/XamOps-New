package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudsitterAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional; // ✅ Import Optional

@Repository
public interface CloudsitterAssignmentRepository extends JpaRepository<CloudsitterAssignment, Long> {

    /**
     * Finds all assignments that are currently marked as active.
     */
    List<CloudsitterAssignment> findAllByActiveTrue();

    /**
     * ✅ ADD THIS METHOD: Finds an assignment by the EC2 instance ID (resourceId).
     * Used to check for duplicates before assigning a new policy.
     */
    Optional<CloudsitterAssignment> findByResourceId(String resourceId);
}