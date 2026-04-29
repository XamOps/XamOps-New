package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AzureCloudsitterAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AzureCloudsitterAssignmentRepository extends JpaRepository<AzureCloudsitterAssignment, Long> {
    List<AzureCloudsitterAssignment> findAllByActiveTrue();
    Optional<AzureCloudsitterAssignment> findByVmNameAndResourceGroupAndSubscriptionId(String vmName, String resourceGroup, String subscriptionId);
    List<AzureCloudsitterAssignment> findAllBySubscriptionIdAndActiveTrue(String subscriptionId);
}
