package com.xammer.cloud.service.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the detector rules against becoming dead code.
 *
 * <p>
 * A detector only ever fires if its event name gets past the {@code xamops-inventory-lifecycle}
 * rule in {@code cloudformation/xamops-inventory-events-nested.yaml}, whose pattern is a
 * prefix allow-list. Adding a rule for {@code PutBucketPolicy} would compile, pass review and
 * never fire once — the event simply never reaches the queue. There is no error to notice,
 * only a detector that finds nothing, which is indistinguishable from a secure account.
 *
 * <p>
 * So the allow-list is duplicated here on purpose. It is the one place a mismatch between the
 * CloudFormation template and the Java can be caught, and the duplication is the mechanism
 * rather than an oversight.
 */
class SecurityFindingDetectorTest {

    /**
     * Copied verbatim from {@code LIFECYCLE_PREFIXES} in the forwarder template. If the
     * template's list changes, this must change with it — that is the point.
     */
    private static final List<String> LIFECYCLE_PREFIXES = List.of(
            "Create", "Run", "Delete", "Terminate", "Allocate",
            "Release", "Register", "Deregister", "Provision", "Deprovision", "Import");

    @Test
    void everyDetectedEventReachesUsUnderTheCurrentEventPattern() {
        for (String eventName : SecurityFindingDetector.detectedEventNames()) {
            boolean forwarded = LIFECYCLE_PREFIXES.stream().anyMatch(eventName::startsWith);
            assertTrue(forwarded,
                    eventName + " does not match any prefix in the xamops-inventory-lifecycle "
                            + "allow-list, so this rule can never fire. Either drop it or add a "
                            + "targeted rule to the CloudFormation template first.");
        }
    }

    /**
     * Copied from {@code EXPOSURE_EVENTS} in the same template. These reach us through the separate
     * {@code xamops-inventory-exposure} rule, which lists exact names rather than prefixes — the
     * prefix allow-list above is the pipeline's cost control and a {@code Put} or {@code Modify}
     * prefix would undo it.
     */
    private static final List<String> EXPOSURE_EVENTS = List.of(
            "PutBucketPolicy", "PutBucketAcl", "PutBucketPublicAccessBlock",
            "PutAccountPublicAccessBlock",
            "AuthorizeSecurityGroupIngress", "ModifySecurityGroupRules",
            "ModifyDBInstance", "ModifyDBCluster", "CreateDBInstance",
            "AttachUserPolicy", "AttachRolePolicy", "PutUserPolicy", "PutRolePolicy",
            "UpdateAssumeRolePolicy", "PutKeyPolicy", "DisableKey", "AddPermission",
            "ModifySnapshotAttribute", "ModifyDBSnapshotAttribute", "ModifyImageAttribute");

    /**
     * The "made public after creation" events are now reachable, and every one has a detector.
     *
     * <p>
     * This is the pairing that has to hold in both directions. An event forwarded with no detector
     * costs money and produces nothing; a detector for an event that is not forwarded reads as a
     * working control and never fires. Neither shows up as an error, so both are asserted here.
     */
    @Test
    void everyForwardedExposureEventHasADetector() {
        for (String eventName : EXPOSURE_EVENTS) {
            assertTrue(ExposureDetector.handledEventNames().contains(eventName),
                    eventName + " is forwarded by the exposure rule but no detector handles it, so "
                            + "the account is paying to deliver an event nobody reads.");
        }
    }

    @Test
    void everyDetectorCorrespondsToAForwardedEvent() {
        for (String eventName : ExposureDetector.handledEventNames()) {
            boolean forwarded = EXPOSURE_EVENTS.contains(eventName)
                    || LIFECYCLE_PREFIXES.stream().anyMatch(eventName::startsWith);
            assertTrue(forwarded,
                    eventName + " has a detector but is not in EXPOSURE_EVENTS and matches no "
                            + "lifecycle prefix, so it can never fire. Add it to the template "
                            + "first.");
        }
    }

    /**
     * The two detector families must not overlap.
     *
     * <p>
     * Ingest tries the exposure detectors first and falls back to the name-only rules, so an event
     * in both would silently take the exposure path and the name-only rule would be dead. Keeping
     * them disjoint means the fallback is a real fallback rather than an accident of ordering.
     */
    @Test
    void nameOnlyAndDetailAwareDetectorsDoNotOverlap() {
        for (String eventName : SecurityFindingDetector.detectedEventNames()) {
            assertFalse(ExposureDetector.handledEventNames().contains(eventName),
                    eventName + " is handled by both detector families; one of them is unreachable.");
        }
    }

    /**
     * Removing Block Public Access is the event this whole detector set exists for, and it is
     * CRITICAL rather than HIGH: it is the control that stops a policy from taking effect, so
     * removing it can expose data instantly with no further action.
     */
    @Test
    void removingBlockPublicAccessIsCritical() {
        Optional<SecurityFindingDetector.Rule> rule =
                SecurityFindingDetector.ruleFor("DeletePublicAccessBlock");
        assertTrue(rule.isPresent());
        assertEquals(SecuritySeverity.CRITICAL, rule.get().severity);
        assertEquals("AwsS3Bucket", rule.get().resourceType);
    }

    /** Destroying audit or detection capability ranks with public exposure. */
    @Test
    void destroyingAuditAndDetectionControlsIsCritical() {
        assertEquals(SecuritySeverity.CRITICAL,
                SecurityFindingDetector.ruleFor("DeleteTrail").orElseThrow().severity);
        assertEquals(SecuritySeverity.CRITICAL,
                SecurityFindingDetector.ruleFor("DeleteDetector").orElseThrow().severity);
    }

    /**
     * Every rule carries remediation text, and that text says the finding must be closed by
     * hand. Nothing tells us Block Public Access came back on — {@code PutPublicAccessBlock}
     * is not forwarded — so these stay ACTIVE until a user resolves them. Auto-resolving on a
     * timer would quietly close real exposures, so the honest fix is to say so in the UI.
     */
    @Test
    void everyRuleExplainsRemediationAndThatItMustBeClosedManually() {
        for (String eventName : SecurityFindingDetector.detectedEventNames()) {
            SecurityFindingDetector.Rule rule = SecurityFindingDetector.ruleFor(eventName).orElseThrow();
            assertFalse(rule.remediation == null || rule.remediation.isBlank(),
                    eventName + " has no remediation — a finding without one is just a complaint");
            assertTrue(rule.remediation.contains("mark it resolved"),
                    eventName + " must tell the user XamOps cannot observe the fix");
            assertFalse(rule.title == null || rule.title.isBlank());
            assertFalse(rule.description == null || rule.description.isBlank());
            assertEquals(SecuritySeverity.normalize(rule.severity), rule.severity,
                    eventName + " uses a severity outside the normalised vocabulary");
        }
    }

    @Test
    void unrelatedEventsProduceNoFinding() {
        assertTrue(SecurityFindingDetector.ruleFor("RunInstances").isEmpty());
        assertTrue(SecurityFindingDetector.ruleFor("CreateBucket").isEmpty(),
                "a new bucket is private by default — flagging every CreateBucket would be noise");
        assertTrue(SecurityFindingDetector.ruleFor(null).isEmpty());
        assertTrue(SecurityFindingDetector.ruleFor("").isEmpty());
    }
}
