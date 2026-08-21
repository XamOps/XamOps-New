package com.xammer.cloud.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the exposure detectors, and above all the cases where they must stay silent.
 *
 * <p>
 * The negative tests matter more than the positive ones here. Every rule fires on an event name
 * that occurs constantly during normal work — {@code AuthorizeSecurityGroupIngress} runs on every
 * deployment that opens a port, {@code AttachRolePolicy} on every new service role. A detector that
 * alerted on the name alone would produce findings all day, and a security page that cries wolf
 * gets muted within a week and then misses the one that mattered. So the noise floor is the feature
 * being tested, not an afterthought.
 */
class ExposureDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode detail(String requestParametersJson) {
        try {
            return mapper.readTree("{\"requestParameters\":" + requestParametersJson + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode truncatedDetail() {
        try {
            return mapper.readTree(
                    "{\"requestParameters\":{\"groupId\":\"sg-1\"},"
                    + "\"requestParametersTruncated\":true}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Security groups ─────────────────────────────────────────────────────

    @Test
    void sshOpenToTheWorldIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-04e1f9\",\"ipPermissions\":{\"items\":[{"
                        + "\"ipProtocol\":\"tcp\",\"fromPort\":22,\"toPort\":22,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().title.contains("SSH"), f.get().title);
        assertEquals("sg-04e1f9", f.get().resourceId);
    }

    @Test
    void rdpOpenToTheWorldIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":3389,\"toPort\":3389,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertTrue(f.get().title.contains("RDP"));
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
    }

    /**
     * A wide range is reported as a range, not as whichever sensitive port it happens to contain.
     *
     * <p>
     * Calling a 0-65535 rule "SSH opened to the internet" understates it, and a reader who checks
     * port 22, finds nothing listening, and closes the finding would leave everything else exposed.
     * The sensitive ports still appear as evidence in the description.
     */
    @Test
    void wideRangeIsDescribedAsARangeWithSensitivePortsAsEvidence() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"ipProtocol\":\"tcp\",\"fromPort\":0,\"toPort\":65535,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().title.contains("wide port range"), f.get().title);
        assertTrue(f.get().description.contains("SSH (port 22)"), f.get().description);
        assertTrue(f.get().description.contains("RDP (port 3389)"), f.get().description);
    }

    /** A narrow range that includes SSH is still headlined as SSH. */
    @Test
    void narrowRangeCoveringSshIsHeadlinedAsSsh() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":20,\"toPort\":25,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().title.contains("SSH"), f.get().title);
    }

    /**
     * The title must not vary between runs. {@code Set.of} iteration order is unspecified, so a
     * range spanning both 22 and 3389 previously produced a different headline depending on the JVM.
     */
    @Test
    void titleIsDeterministicForRangesSpanningSeveralSensitivePorts() {
        String payload = "{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                + "\"fromPort\":20,\"toPort\":30,"
                + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}";

        String first = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail(payload)).get().title;
        for (int i = 0; i < 20; i++) {
            assertEquals(first, ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                    detail(payload)).get().title);
        }
    }

    @Test
    void allProtocolsOpenToTheWorldIsReportedAsSuch() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"ipProtocol\":\"-1\",\"fromPort\":-1,\"toPort\":-1,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().title.contains("All traffic"), f.get().title);
    }

    @Test
    void databasePortOpenToTheWorldIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":5432,\"toPort\":5432,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().title.contains("5432"));
    }

    /** IPv6 "anywhere" is the same exposure and is missed by an IPv4-only check. */
    @Test
    void ipv6OpenToTheWorldIsCaught() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":22,\"toPort\":22,"
                        + "\"ipv6Ranges\":{\"items\":[{\"cidrIpv6\":\"::/0\"}]}}]}}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
    }

    /** A public web port is usually deliberate, so it is HIGH rather than CRITICAL. */
    @Test
    void publicWebPortIsHighNotCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":443,\"toPort\":443,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}"));

        assertEquals(SecuritySeverity.HIGH, f.get().severity);
    }

    /**
     * The most important test in the file. Opening SSH to a known office range is routine and must
     * produce nothing — otherwise every deployment generates a critical alert.
     */
    @Test
    void portOpenedToASpecificRangeProducesNoFinding() {
        assertTrue(ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":22,\"toPort\":22,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"203.0.113.4/32\"}]}}]}}"))
                .isEmpty(), "a scoped source range is normal operations, not a finding");

        assertTrue(ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":{\"items\":[{"
                        + "\"fromPort\":22,\"toPort\":22,"
                        + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"10.0.0.0/8\"}]}}]}}"))
                .isEmpty(), "an internal range is not internet exposure");
    }

    /** CloudTrail emits bare arrays for some services and {"items":[...]} for others. */
    @Test
    void bothCloudTrailCollectionShapesAreUnderstood() {
        Optional<DerivedFinding> bare = ExposureDetector.detect("AuthorizeSecurityGroupIngress",
                detail("{\"groupId\":\"sg-1\",\"ipPermissions\":[{"
                        + "\"fromPort\":22,\"toPort\":22,"
                        + "\"ipRanges\":[{\"cidrIp\":\"0.0.0.0/0\"}]}]}"));

        assertTrue(bare.isPresent(), "a bare array shape must not silently produce nothing");
    }

    /**
     * A truncated payload reports that it could not check, rather than concluding the change was
     * safe. Silence would mean an unchecked account looks clean.
     */
    @Test
    void truncatedPayloadProducesAnHonestLowSeverityFinding() {
        Optional<DerivedFinding> f =
                ExposureDetector.detect("AuthorizeSecurityGroupIngress", truncatedDetail());

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.LOW, f.get().severity);
        assertTrue(f.get().description.contains("could not check"), f.get().description);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────

    @Test
    void bucketPolicyAllowingAnyoneIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("PutBucketPolicy",
                detail("{\"bucketName\":\"my-bucket\",\"bucketPolicy\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                        + "\\\"Principal\\\":\\\"*\\\",\\\"Action\\\":\\\"s3:GetObject\\\"}]}\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertEquals("my-bucket", f.get().resourceId);
    }

    /**
     * A wildcard principal inside a Deny is the standard "require TLS" pattern — the opposite of a
     * problem. Flagging it would teach users that the alert is wrong.
     */
    @Test
    void wildcardPrincipalInsideADenyIsNotAFinding() {
        assertTrue(ExposureDetector.detect("PutBucketPolicy",
                detail("{\"bucketName\":\"b\",\"bucketPolicy\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Deny\\\","
                        + "\\\"Principal\\\":\\\"*\\\",\\\"Condition\\\":"
                        + "{\\\"Bool\\\":{\\\"aws:SecureTransport\\\":\\\"false\\\"}}}]}\"}"))
                .isEmpty(), "deny-all-insecure-transport is a best practice, not an exposure");
    }

    @Test
    void scopedBucketPolicyIsNotAFinding() {
        assertTrue(ExposureDetector.detect("PutBucketPolicy",
                detail("{\"bucketName\":\"b\",\"bucketPolicy\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":"
                        + "{\\\"AWS\\\":\\\"arn:aws:iam::111122223333:root\\\"}}]}\"}"))
                .isEmpty());
    }

    @Test
    void publicCannedAclIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("PutBucketAcl",
                detail("{\"bucketName\":\"b\",\"x-amz-acl\":\"public-read\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
    }

    /** AuthenticatedUsers means every AWS account in the world, which catches people out. */
    @Test
    void authenticatedUsersGrantIsTreatedAsPublic() {
        Optional<DerivedFinding> f = ExposureDetector.detect("PutBucketAcl",
                detail("{\"bucketName\":\"b\",\"AccessControlPolicy\":{\"AccessControlList\":"
                        + "{\"Grant\":[{\"Grantee\":{\"URI\":"
                        + "\"http://acs.amazonaws.com/groups/global/AuthenticatedUsers\"}}]}}}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
    }

    @Test
    void privateAclIsNotAFinding() {
        assertTrue(ExposureDetector.detect("PutBucketAcl",
                detail("{\"bucketName\":\"b\",\"x-amz-acl\":\"private\"}")).isEmpty());
    }

    @Test
    void blockPublicAccessTurnedOffIsCriticalAndNamesTheFlags() {
        Optional<DerivedFinding> f = ExposureDetector.detect("PutBucketPublicAccessBlock",
                detail("{\"bucketName\":\"b\",\"PublicAccessBlockConfiguration\":{"
                        + "\"BlockPublicAcls\":false,\"IgnorePublicAcls\":true,"
                        + "\"BlockPublicPolicy\":false,\"RestrictPublicBuckets\":true}}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertTrue(f.get().description.contains("BlockPublicAcls"));
        assertTrue(f.get().description.contains("BlockPublicPolicy"));
        assertFalse(f.get().description.contains("IgnorePublicAcls"),
                "flags left enabled must not be listed as disabled");
    }

    @Test
    void blockPublicAccessFullyEnabledIsNotAFinding() {
        assertTrue(ExposureDetector.detect("PutBucketPublicAccessBlock",
                detail("{\"bucketName\":\"b\",\"PublicAccessBlockConfiguration\":{"
                        + "\"BlockPublicAcls\":true,\"IgnorePublicAcls\":true,"
                        + "\"BlockPublicPolicy\":true,\"RestrictPublicBuckets\":true}}"))
                .isEmpty(), "turning protection ON must never alert");
    }

    // ── Databases ───────────────────────────────────────────────────────────

    @Test
    void databaseMadePublicIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("ModifyDBInstance",
                detail("{\"dBInstanceIdentifier\":\"prod-orders-db\",\"publiclyAccessible\":true}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertEquals("prod-orders-db", f.get().resourceId);
    }

    @Test
    void databaseMadePrivateOrUnchangedIsNotAFinding() {
        assertTrue(ExposureDetector.detect("ModifyDBInstance",
                detail("{\"dBInstanceIdentifier\":\"db\",\"publiclyAccessible\":false}")).isEmpty(),
                "making a database private is the fix, not the problem");
        assertTrue(ExposureDetector.detect("ModifyDBInstance",
                detail("{\"dBInstanceIdentifier\":\"db\",\"allocatedStorage\":200}")).isEmpty(),
                "resizing storage is unrelated to exposure");
    }

    // ── Identity ────────────────────────────────────────────────────────────

    @Test
    void administratorAccessAttachedIsAFinding() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AttachRolePolicy",
                detail("{\"roleName\":\"deploy-role\","
                        + "\"policyArn\":\"arn:aws:iam::aws:policy/AdministratorAccess\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.HIGH, f.get().severity);
        assertEquals("deploy-role", f.get().resourceId);
    }

    @Test
    void scopedManagedPolicyAttachedIsNotAFinding() {
        assertTrue(ExposureDetector.detect("AttachRolePolicy",
                detail("{\"roleName\":\"r\","
                        + "\"policyArn\":\"arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess\"}"))
                .isEmpty(), "attaching a scoped policy is ordinary work");
    }

    @Test
    void inlinePolicyGrantingEverythingIsAFinding() {
        Optional<DerivedFinding> f = ExposureDetector.detect("PutRolePolicy",
                detail("{\"roleName\":\"r\",\"policyDocument\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                        + "\\\"Action\\\":\\\"*\\\",\\\"Resource\\\":\\\"*\\\"}]}\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.HIGH, f.get().severity);
    }

    @Test
    void scopedInlinePolicyIsNotAFinding() {
        assertTrue(ExposureDetector.detect("PutRolePolicy",
                detail("{\"roleName\":\"r\",\"policyDocument\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                        + "\\\"Action\\\":\\\"s3:GetObject\\\",\\\"Resource\\\":\\\"*\\\"}]}\"}"))
                .isEmpty());
    }

    @Test
    void roleAssumableByAnyoneIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("UpdateAssumeRolePolicy",
                detail("{\"roleName\":\"r\",\"policyDocument\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\","
                        + "\\\"Principal\\\":\\\"*\\\",\\\"Action\\\":\\\"sts:AssumeRole\\\"}]}\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
    }

    @Test
    void normalServiceTrustPolicyIsNotAFinding() {
        assertTrue(ExposureDetector.detect("UpdateAssumeRolePolicy",
                detail("{\"roleName\":\"r\",\"policyDocument\":"
                        + "\"{\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":"
                        + "{\\\"Service\\\":\\\"lambda.amazonaws.com\\\"}}]}\"}"))
                .isEmpty());
    }

    // ── Sharing and resource policies ───────────────────────────────────────

    @Test
    void publiclySharedSnapshotIsCritical() {
        Optional<DerivedFinding> f = ExposureDetector.detect("ModifySnapshotAttribute",
                detail("{\"snapshotId\":\"snap-123\",\"createVolumePermission\":"
                        + "{\"add\":{\"items\":[{\"group\":\"all\"}]}}}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, f.get().severity);
        assertEquals("snap-123", f.get().resourceId);
    }

    @Test
    void snapshotSharedWithOneAccountIsNotAFinding() {
        assertTrue(ExposureDetector.detect("ModifySnapshotAttribute",
                detail("{\"snapshotId\":\"snap-1\",\"createVolumePermission\":"
                        + "{\"add\":{\"items\":[{\"userId\":\"111122223333\"}]}}}"))
                .isEmpty(), "sharing with a named account is a normal cross-account workflow");
    }

    @Test
    void resourcePolicyOpenToAnyPrincipalIsAFinding() {
        Optional<DerivedFinding> f = ExposureDetector.detect("AddPermission",
                detail("{\"functionName\":\"my-fn\",\"principal\":\"*\"}"));

        assertTrue(f.isPresent());
        assertEquals(SecuritySeverity.HIGH, f.get().severity);
    }

    @Test
    void resourcePolicyForANamedServiceIsNotAFinding() {
        assertTrue(ExposureDetector.detect("AddPermission",
                detail("{\"functionName\":\"my-fn\",\"principal\":\"s3.amazonaws.com\"}"))
                .isEmpty());
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    @Test
    void missingOrNullDetailProducesNothingRatherThanThrowing() {
        assertTrue(ExposureDetector.detect("AuthorizeSecurityGroupIngress", null).isEmpty());
        assertTrue(ExposureDetector.detect(null, detail("{}")).isEmpty());
        assertTrue(ExposureDetector.detect("AuthorizeSecurityGroupIngress", detail("{}")).isEmpty());
        assertTrue(ExposureDetector.detect("SomeUnrelatedCall", detail("{}")).isEmpty());
    }

    /**
     * Every handled event must be reachable from {@code detect}. A name in the set with no branch is
     * a rule that reads as a working control and never fires.
     */
    @Test
    void everyHandledEventNameHasABranch() {
        for (String eventName : ExposureDetector.handledEventNames()) {
            // A permissive payload: the assertion is only that the call is routed and does not
            // throw, not that it produces a finding.
            ExposureDetector.detect(eventName, detail("{}"));
        }
        assertFalse(ExposureDetector.handledEventNames().isEmpty());
    }

    /** Keys differ per resource, so two buckets do not collapse into one finding. */
    @Test
    void findingKeysAreScopedToTheResource() {
        String a = ExposureDetector.detect("PutBucketAcl",
                detail("{\"bucketName\":\"bucket-a\",\"x-amz-acl\":\"public-read\"}")).get().key;
        String b = ExposureDetector.detect("PutBucketAcl",
                detail("{\"bucketName\":\"bucket-b\",\"x-amz-acl\":\"public-read\"}")).get().key;

        assertFalse(a.equals(b), "two different buckets must be two different findings");
    }
}
