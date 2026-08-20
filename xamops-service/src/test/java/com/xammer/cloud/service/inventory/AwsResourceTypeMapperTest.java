package com.xammer.cloud.service.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the display-type vocabulary.
 *
 * <p>
 * These assertions look trivial and are the most load-bearing tests in the change.
 * Consumers filter on these exact strings by equality — {@code XamOpsRightsizingService}
 * keeps only {@code "EC2 Instance"}, {@code CloudMapService} only {@code "VPC"} — so
 * renaming one here empties a page with no exception and no log line. If one of these
 * fails, the fix is almost never to update the test.
 */
class AwsResourceTypeMapperTest {

    // ── Contract with existing consumers ────────────────────────────────────

    @Test
    void mapsTypesThatDownstreamConsumersFilterOnByEquality() {
        // XamOpsRightsizingService.getLiveRecommendations filters on this literal.
        assertEquals("EC2 Instance", AwsResourceTypeMapper.displayTypeFor("ec2:instance"));
        // CloudMapService.getVpcListForCloudmap filters on this literal.
        assertEquals("VPC", AwsResourceTypeMapper.displayTypeFor("ec2:vpc"));
        // CloudGuardService.getVpcQuotaAlerts counts these per region.
        assertEquals("EBS Volume", AwsResourceTypeMapper.displayTypeFor("ec2:volume"));
    }

    @Test
    void matchesCloudListServiceDisplayStringsExactly() {
        // Every expectation below is copied from a new ResourceDto(...) call in
        // CloudListService, including the ones whose casing is inconsistent.
        assertEquals("RDS Instance", AwsResourceTypeMapper.displayTypeFor("rds:db"));
        assertEquals("RDS Cluster (Aurora)", AwsResourceTypeMapper.displayTypeFor("rds:cluster"));
        assertEquals("S3 Bucket", AwsResourceTypeMapper.displayTypeFor("s3:bucket"));
        assertEquals("Lambda Function", AwsResourceTypeMapper.displayTypeFor("lambda:function"));
        assertEquals("Route 53 Zone", AwsResourceTypeMapper.displayTypeFor("route53:hostedzone"));
        assertEquals("Amazon Athena", AwsResourceTypeMapper.displayTypeFor("athena:workgroup"));
        assertEquals("AWS Step Functions", AwsResourceTypeMapper.displayTypeFor("states:stateMachine"));
        assertEquals("KMS", AwsResourceTypeMapper.displayTypeFor("kms:key"));
        assertEquals("CloudWatch Log Group", AwsResourceTypeMapper.displayTypeFor("logs:log-group"));
        assertEquals("Load Balancer",
                AwsResourceTypeMapper.displayTypeFor("elasticloadbalancing:loadbalancer"));
        assertEquals("Auto Scaling Group",
                AwsResourceTypeMapper.displayTypeFor("autoscaling:autoScalingGroup"));
    }

    @Test
    void mapsBothOpenSearchTypeTokensToOneDisplayType() {
        // Resource Explorer reports the legacy "es" service for older domains, so both
        // must collapse to one group or the cloud list shows the same thing twice.
        assertEquals("OpenSearch Domain", AwsResourceTypeMapper.displayTypeFor("es:domain"));
        assertEquals("OpenSearch Domain", AwsResourceTypeMapper.displayTypeFor("opensearch:domain"));
    }

    // ── Unmapped types must stay visible ────────────────────────────────────

    @Test
    void humanisesUnmappedTypesInsteadOfDroppingThem() {
        // A service AWS launches next month must still reach the cloud list. Filtering
        // to a known allow-list is how an inventory silently goes stale.
        String display = AwsResourceTypeMapper.displayTypeFor("bogusservice:widget-thing");
        assertTrue(display.contains("Widget"), "expected humanised type, got: " + display);
        assertTrue(display.contains("Thing"), "expected humanised type, got: " + display);
    }

    @Test
    void neverReturnsNullOrEmptyForDegenerateInput() {
        // displayType is NOT NULL in the schema, so a null here becomes a failed insert
        // that aborts the whole account sync.
        assertEquals("Unknown", AwsResourceTypeMapper.displayTypeFor(null));
        assertEquals("Unknown", AwsResourceTypeMapper.displayTypeFor(""));
        assertEquals("Unknown", AwsResourceTypeMapper.displayTypeFor("   "));
        assertFalse(AwsResourceTypeMapper.displayTypeFor("weird").isBlank());
    }

    @Test
    void suppressesStackSetTargetsWhichWouldDoubleCountEveryManagedResource() {
        assertTrue(AwsResourceTypeMapper.isSuppressed("cloudformation:stackset-target"));
        assertFalse(AwsResourceTypeMapper.isSuppressed("ec2:instance"));
        assertFalse(AwsResourceTypeMapper.isSuppressed(null));
    }

    // ── ARN parsing ─────────────────────────────────────────────────────────

    @Test
    void extractsShortResourceIdFromSlashStyleArns() {
        assertEquals("i-0abc123def456",
                AwsResourceTypeMapper.resourceIdFromArn(
                        "arn:aws:ec2:us-east-1:123456789012:instance/i-0abc123def456"));
        assertEquals("vol-0abc123",
                AwsResourceTypeMapper.resourceIdFromArn(
                        "arn:aws:ec2:ap-south-1:123456789012:volume/vol-0abc123"));
    }

    @Test
    void extractsResourceIdFromColonStyleArns() {
        // RDS and Lambda use a colon before the resource name, not a slash.
        assertEquals("my-database",
                AwsResourceTypeMapper.resourceIdFromArn(
                        "arn:aws:rds:us-east-1:123456789012:db:my-database"));
        // Must be the bare function name, matching what CloudListService stores as
        // ResourceDto.id (f.functionName()), not "function:my-fn".
        assertEquals("my-fn",
                AwsResourceTypeMapper.resourceIdFromArn(
                        "arn:aws:lambda:us-east-1:123456789012:function:my-fn"));
    }

    @Test
    void handlesS3ArnsWhichOmitRegionAndAccount() {
        assertEquals("my-bucket-name",
                AwsResourceTypeMapper.resourceIdFromArn("arn:aws:s3:::my-bucket-name"));
    }

    @Test
    void fallsBackToTheWholeArnRatherThanReturningNothing() {
        // A non-ARN string is still a usable unique id; null would break the NOT NULL
        // resource_id column and lose the resource entirely.
        assertEquals("not-an-arn", AwsResourceTypeMapper.resourceIdFromArn("not-an-arn"));
        assertEquals("", AwsResourceTypeMapper.resourceIdFromArn(""));
    }
}
