package com.xammer.cloud.service.inventory;

import com.xammer.cloud.dto.DashboardData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the dashboard Resource Inventory widget's numbers to the display-type strings that
 * actually reach it.
 *
 * <p>
 * This mapping is keyed on strings, so every mistake it can make is silent: a key that does
 * not match simply yields zero, and a widget reading 0 looks like an account that owns nothing
 * rather than a bug. That already happened twice — snapshots were looked up under "Snapshot"
 * while both producers emit "EBS Snapshot", so the count was permanently zero; and the widget
 * served cached counts that disagreed with the cloud list beside it.
 */
class ResourceInventoryMapperTest {

    /** The keys are the exact strings AwsResourceTypeMapper emits. */
    @Test
    void mapsDisplayTypeCountsOntoWidgetFields() {
        DashboardData.ResourceInventory inv = ResourceInventoryMapper.fromCounts(Map.of(
                "EC2 Instance", 20,
                "S3 Bucket", 23,
                "VPC", 29,
                "Lambda Function", 6,
                "EBS Volume", 20,
                "Route 53 Zone", 3,
                "Load Balancer", 5,
                "EBS Snapshot", 23));

        assertEquals(20, inv.getEc2Count());
        assertEquals(23, inv.getS3Count());
        assertEquals(29, inv.getVpcCount());
        assertEquals(6, inv.getLambdaCount());
        assertEquals(20, inv.getEbsCount());
        assertEquals(3, inv.getRoute53Count());
        assertEquals(5, inv.getLoadBalancerCount());
        // Was looked up as "Snapshot" and therefore always zero.
        assertEquals(23, inv.getSnapshots());
    }

    /**
     * The frontend reads the {@code *Count} aliases while older consumers read the plain
     * fields. Both must carry the same value or the two disagree depending on the reader.
     */
    @Test
    void aliasFieldsMatchTheirCanonicalCounterparts() {
        DashboardData.ResourceInventory inv = ResourceInventoryMapper.fromCounts(Map.of(
                "EC2 Instance", 7, "S3 Bucket", 11, "VPC", 2, "EKS Cluster", 1,
                "ECS Cluster", 4, "RDS Instance", 3, "Lightsail Instance", 5,
                "Amplify App", 6));

        assertEquals(inv.getEc2(), inv.getEc2Count());
        assertEquals(inv.getS3Buckets(), inv.getS3Count());
        assertEquals(inv.getVpc(), inv.getVpcCount());
        assertEquals(inv.getKubernetes(), inv.getEksCount());
        assertEquals(inv.getEcs(), inv.getEcsCount());
        assertEquals(inv.getRdsInstances(), inv.getRdsCount());
        assertEquals(inv.getLightsail(), inv.getLightsailCount());
        assertEquals(inv.getAmplify(), inv.getAmplifyCount());
    }

    /** Absent types are zero, not null, so the widget renders a row rather than breaking. */
    @Test
    void absentTypesAreZero() {
        DashboardData.ResourceInventory inv = ResourceInventoryMapper.fromCounts(Map.of());
        assertEquals(0, inv.getEc2Count());
        assertEquals(0, inv.getS3Count());
        assertEquals(0, inv.getSnapshots());
    }

    /** The plane's counts arrive as longs from a SQL COUNT and must map identically. */
    @Test
    void longCountsFromSqlMapTheSameWayAsIntCounts() {
        DashboardData.ResourceInventory fromLongs = ResourceInventoryMapper.fromLongCounts(
                Map.of("EC2 Instance", 20L, "S3 Bucket", 23L));
        DashboardData.ResourceInventory fromInts = ResourceInventoryMapper.fromCounts(
                Map.of("EC2 Instance", 20, "S3 Bucket", 23));

        assertEquals(fromInts.getEc2Count(), fromLongs.getEc2Count());
        assertEquals(fromInts.getS3Count(), fromLongs.getS3Count());
    }
}
