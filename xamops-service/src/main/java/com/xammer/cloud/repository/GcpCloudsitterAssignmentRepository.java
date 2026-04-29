package com.xammer.cloud.repository;

import com.xammer.cloud.domain.GcpCloudsitterAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GcpCloudsitterAssignmentRepository extends JpaRepository<GcpCloudsitterAssignment, Long> {
    List<GcpCloudsitterAssignment> findAllByActiveTrue();
    Optional<GcpCloudsitterAssignment> findByInstanceNameAndZoneAndGcpProjectId(String instanceName, String zone, String gcpProjectId);
    List<GcpCloudsitterAssignment> findAllByGcpProjectIdAndActiveTrue(String gcpProjectId);
}
