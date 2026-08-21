package com.xammer.cloud.service.inventory;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the EC2 instance attribute keys the plane writes into {@code details}.
 *
 * <p>
 * Regression test for the plane storing {@code "{}"} as the details of every row. Resource
 * Explorer reports no attributes, so anything a consumer reads out of {@code details} has to
 * be harvested from a describe — and until it was, turning {@code xamops.inventory.enabled}
 * on emptied the Rightsizing page and flattened the Cloud Map graph.
 *
 * <p>
 * The failure mode is what makes this worth pinning: both consumers look up a key by literal
 * string and treat a miss as "nothing to show". {@code XamOpsRightsizingService} skips any
 * instance whose {@code "Type"} is null and {@code CloudMapService} groups nodes by
 * {@code "VPC ID"}, so a renamed or dropped key produces an empty page with no error in any
 * log. Comparing against the strings {@code CloudListService.fetchEc2InstancesForCloudlist}
 * emits is the only thing that catches it.
 */
class InventoryInstanceDetailsTest {

    private static Instance fullInstance() {
        return Instance.builder()
                .instanceId("i-abc123")
                .instanceType("t3.medium")
                .imageId("ami-0123456789")
                .vpcId("vpc-aaa111")
                .subnetId("subnet-bbb222")
                .privateIpAddress("10.0.1.15")
                .securityGroups(
                        GroupIdentifier.builder().groupId("sg-111").build(),
                        GroupIdentifier.builder().groupId("sg-222").build())
                .build();
    }

    /**
     * Exact strings, copied from the describe path. Not "equivalent" keys — consumers do a
     * literal map lookup, so casing and spacing are part of the contract.
     */
    @Test
    void keysMatchTheDescribePathExactly() {
        Map<String, String> details = AwsInventoryReconciler.instanceDetails(fullInstance());

        assertEquals("t3.medium", details.get("Type"));
        assertEquals("ami-0123456789", details.get("Image ID"));
        assertEquals("vpc-aaa111", details.get("VPC ID"));
        assertEquals("subnet-bbb222", details.get("Subnet ID"));
        assertEquals("10.0.1.15", details.get("Private IP"));
        assertEquals("sg-111, sg-222", details.get("Security Groups"));
    }

    /**
     * The single key Rightsizing cannot work without. Called out separately from the sweep
     * above so a failure names the page that breaks.
     */
    @Test
    void instanceTypeIsPresentBecauseRightsizingSkipsInstancesWithout() {
        assertEquals("t3.medium", AwsInventoryReconciler.instanceDetails(fullInstance()).get("Type"));
    }

    /**
     * Absent, not empty-string. {@code CloudMapService} splits global from VPC-scoped
     * resources on {@code getDetail(r, "VPC ID") == null}, so a blank value would file an
     * EC2-Classic instance as a member of a VPC whose id is the empty string — and it would
     * then vanish from the global view rather than showing up anywhere obvious.
     */
    @Test
    void missingAttributesAreOmittedRatherThanBlanked() {
        Instance sparse = Instance.builder()
                .instanceId("i-sparse")
                .instanceType("t2.micro")
                .build();

        Map<String, String> details = AwsInventoryReconciler.instanceDetails(sparse);

        assertEquals("t2.micro", details.get("Type"));
        assertFalse(details.containsKey("VPC ID"), "a null vpcId must not become \"\"");
        assertFalse(details.containsKey("Subnet ID"));
        assertFalse(details.containsKey("Private IP"));
        assertFalse(details.containsKey("Image ID"));
        assertFalse(details.containsKey("Security Groups"),
                "an instance with no security groups must not carry an empty joined string");
    }

    /**
     * An instance carrying nothing useful yields an empty map, which the reconciler treats as
     * "resolved nothing" and so leaves the previous details in place rather than overwriting
     * good values with blanks.
     */
    @Test
    void emptyInstanceYieldsEmptyDetailsSoTheUpsertLeavesRowsAlone() {
        assertTrue(AwsInventoryReconciler.instanceDetails(Instance.builder().build()).isEmpty());
    }
}
