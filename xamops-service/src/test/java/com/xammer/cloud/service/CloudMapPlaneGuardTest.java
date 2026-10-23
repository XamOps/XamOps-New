package com.xammer.cloud.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xammer.cloud.dto.ResourceDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins when the Cloud Map may serve its graph from the inventory plane.
 *
 * On UAT the plane carried a VPC ID for instances and network interfaces only, and the old
 * "any resource has a VPC ID" check let it answer, so every load balancer, NAT gateway and
 * internet gateway vanished from the map.
 */
class CloudMapPlaneGuardTest {

    private static ResourceDto res(String type, String vpc) {
        return new ResourceDto("id-" + type + vpc, type, type, "ap-south-1", "available", null,
                vpc == null ? Map.of() : Map.of("VPC ID", vpc));
    }

    @Test
    void rejectsPlaneThatPlacesOnlyInstances() {
        assertFalse(CloudMapService.planeCarriesNetworkTopology(List.of(
                res("EC2 Instance", "vpc-1"),
                res("Network Interface", "vpc-1"),
                res("Load Balancer", null),
                res("NAT Gateway", null))));
    }

    @Test
    void rejectsPlaneWhenSecurityGroupsAreUnplaced() {
        assertFalse(CloudMapService.planeCarriesNetworkTopology(List.of(
                res("EC2 Instance", "vpc-1"),
                res("Security Group", "N/A"))));
    }

    @Test
    void acceptsPlaneThatPlacesNetworkResources() {
        assertTrue(CloudMapService.planeCarriesNetworkTopology(List.of(
                res("EC2 Instance", "vpc-1"),
                res("Load Balancer", "vpc-1"),
                res("Security Group", "vpc-1"),
                res("Internet Gateway", "vpc-1"))));
    }

    @Test
    void acceptsPlaneWithNoNetworkScopedTypes() {
        // Nothing to lose: an account of instances and VPC-less services.
        assertTrue(CloudMapService.planeCarriesNetworkTopology(List.of(
                res("EC2 Instance", "vpc-1"),
                res("Lambda Function", null),
                res("S3 Bucket", null))));
    }
}
