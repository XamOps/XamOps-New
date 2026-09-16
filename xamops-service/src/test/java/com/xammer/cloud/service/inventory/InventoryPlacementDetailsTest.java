package com.xammer.cloud.service.inventory;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceAttachment;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBSubnetGroup;
import software.amazon.awssdk.services.rds.model.VpcSecurityGroupMembership;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the placement attributes the plane writes for volumes, databases and interfaces.
 *
 * <p>
 * Companion to {@link InventoryInstanceDetailsTest}, which covers EC2 instances. Instances
 * were for a long time the only type whose details were harvested from a describe, so serving
 * the Cloud Map graph from the plane produced a VPC containing nothing but EC2: every other
 * type was stored with empty details, and {@code CloudMapService} places a resource by its
 * {@code "VPC ID"} and silently drops anything without one.
 *
 * <p>
 * These keys are read by literal string, so a rename is invisible until a page comes back
 * empty. That is the whole reason for asserting exact strings rather than "some vpc key".
 */
class InventoryPlacementDetailsTest {

    // ── EBS volumes ───────────────────────────────────────────────────────────────

    /**
     * The attachment is what lets Cloud Map draw {@code EBS Volume -> "attaches to" -> EC2},
     * so it is the one detail a volume must carry.
     */
    @Test
    void volumeCarriesTheInstanceItIsAttachedTo() {
        Volume v = Volume.builder()
                .volumeId("vol-abc")
                .size(100)
                .volumeType("gp3")
                .availabilityZone("ap-south-1a")
                .state("in-use")
                .attachments(VolumeAttachment.builder().instanceId("i-abc123").build())
                .build();

        Map<String, String> details = AwsInventoryReconciler.volumeDetails(v);

        assertEquals("i-abc123", details.get("Attached to"));
        assertEquals("gp3", details.get("Type"));
        assertEquals("100 GiB", details.get("Size"));
        assertEquals("ap-south-1a", details.get("Availability Zone"));
    }

    @Test
    void unattachedVolumeOmitsAttachmentRatherThanBlankingIt() {
        Volume v = Volume.builder().volumeId("vol-loose").size(8).volumeType("gp2").build();

        Map<String, String> details = AwsInventoryReconciler.volumeDetails(v);

        assertFalse(details.containsKey("Attached to"),
                "an unattached volume must not claim an empty attachment");
        assertEquals("gp2", details.get("Type"));
    }

    // ── RDS instances ─────────────────────────────────────────────────────────────

    /**
     * The VPC id sits on the DB subnet group, not the instance, which is why it was missed.
     */
    @Test
    void databaseTakesItsVpcFromTheSubnetGroup() {
        DBInstance d = DBInstance.builder()
                .dbInstanceIdentifier("db-prod")
                .engine("postgres")
                .dbInstanceClass("db.t3.medium")
                .dbSubnetGroup(DBSubnetGroup.builder().vpcId("vpc-aaa111").build())
                .vpcSecurityGroups(
                        VpcSecurityGroupMembership.builder().vpcSecurityGroupId("sg-111").build(),
                        VpcSecurityGroupMembership.builder().vpcSecurityGroupId("sg-222").build())
                .build();

        Map<String, String> details = AwsInventoryReconciler.dbInstanceDetails(d);

        assertEquals("vpc-aaa111", details.get("VPC ID"));
        assertEquals("postgres", details.get("Engine"));
        assertEquals("db.t3.medium", details.get("Type"));
        assertEquals("sg-111, sg-222", details.get("Security Groups"));
    }

    /**
     * A DB subnet group spans several subnets by design. {@code getParent} prefers
     * {@code "Subnet ID"} when it is present, so emitting one would pin a multi-AZ database
     * inside whichever subnet happened to be listed first — a claim the data cannot support.
     */
    @Test
    void databaseIsPlacedInTheVpcAndNotInOneArbitrarySubnet() {
        DBInstance d = DBInstance.builder()
                .dbInstanceIdentifier("db-multi-az")
                .dbSubnetGroup(DBSubnetGroup.builder().vpcId("vpc-aaa111").build())
                .build();

        Map<String, String> details = AwsInventoryReconciler.dbInstanceDetails(d);

        assertEquals("vpc-aaa111", details.get("VPC ID"));
        assertFalse(details.containsKey("Subnet ID"),
                "a multi-subnet database must not be pinned into a single subnet");
    }

    /**
     * A classic, non-VPC database has no subnet group at all. Reading through it must not
     * throw, and must not invent a VPC membership.
     */
    @Test
    void databaseWithNoSubnetGroupOmitsVpcRatherThanFailing() {
        DBInstance d = DBInstance.builder().dbInstanceIdentifier("db-classic").engine("mysql").build();

        Map<String, String> details = AwsInventoryReconciler.dbInstanceDetails(d);

        assertEquals("mysql", details.get("Engine"));
        assertFalse(details.containsKey("VPC ID"));
        assertFalse(details.containsKey("Security Groups"));
    }

    // ── Network interfaces ────────────────────────────────────────────────────────

    @Test
    void interfaceCarriesVpcSubnetAndAttachment() {
        NetworkInterface n = NetworkInterface.builder()
                .networkInterfaceId("eni-abc")
                .vpcId("vpc-aaa111")
                .subnetId("subnet-bbb222")
                .privateIpAddress("10.0.1.20")
                .interfaceType("interface")
                .attachment(NetworkInterfaceAttachment.builder().instanceId("i-abc123").build())
                .groups(GroupIdentifier.builder().groupId("sg-333").build())
                .build();

        Map<String, String> details = AwsInventoryReconciler.networkInterfaceDetails(n);

        assertEquals("vpc-aaa111", details.get("VPC ID"));
        assertEquals("subnet-bbb222", details.get("Subnet ID"));
        assertEquals("10.0.1.20", details.get("Private IP"));
        assertEquals("i-abc123", details.get("Attached to"));
        assertEquals("sg-333", details.get("Security Groups"));
    }

    @Test
    void detachedInterfaceOmitsAttachment() {
        NetworkInterface n = NetworkInterface.builder()
                .networkInterfaceId("eni-loose")
                .vpcId("vpc-aaa111")
                .subnetId("subnet-bbb222")
                .build();

        Map<String, String> details = AwsInventoryReconciler.networkInterfaceDetails(n);

        assertEquals("vpc-aaa111", details.get("VPC ID"));
        assertFalse(details.containsKey("Attached to"));
        assertFalse(details.containsKey("Security Groups"));
    }

    /**
     * An empty map signals "resolved nothing", which the reconciler's upsert treats as a
     * reason to leave existing details alone rather than overwrite good values with blanks.
     */
    @Test
    void emptyInputsYieldEmptyDetailsSoTheUpsertLeavesRowsAlone() {
        assertTrue(AwsInventoryReconciler.volumeDetails(Volume.builder().build()).isEmpty());
        assertTrue(AwsInventoryReconciler.dbInstanceDetails(DBInstance.builder().build()).isEmpty());
        assertTrue(AwsInventoryReconciler.networkInterfaceDetails(
                NetworkInterface.builder().build()).isEmpty());
    }
}
