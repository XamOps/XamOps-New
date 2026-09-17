package com.xammer.cloud.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import com.xammer.cloud.repository.SecurityFindingResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers the four event shapes and the idempotency they depend on.
 *
 * <p>
 * Idempotency is not defensive here, it is required. SQS is at-least-once, GuardDuty
 * re-publishes subsequent occurrences of the same finding on its own cadence (six hours by
 * default), and Security Hub re-imports on every state change. A pipeline keyed on anything
 * other than the provider's finding id accumulates a row per redelivery, so an account under
 * sustained attack — the case where the page matters most — is the case where it degrades.
 */
class SecurityFindingIngestServiceTest {

    private CloudSecurityFindingRepository repository;
    private SecurityFindingResolutionRepository resolutionRepository;
    private SecurityFindingIngestService service;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Stands in for the table, keyed the way the unique constraint is. */
    private Map<String, CloudSecurityFinding> stored;

    private static final Long CLOUD_ACCOUNT_ID = 42L;
    private static final String AWS_ACCOUNT = "123456789012";

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(CloudSecurityFindingRepository.class);
        stored = new HashMap<>();

        when(repository.findByCloudAccountIdAndFindingId(anyLong(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get((String) inv.getArgument(1))));
        when(repository.save(any(CloudSecurityFinding.class))).thenAnswer(inv -> {
            CloudSecurityFinding row = inv.getArgument(0);
            stored.put(row.getFindingId(), row);
            return row;
        });

        // Retiring a "user marked this fixed" claim when the provider reports the finding again is
        // a side effect of ingest, not something these tests assert on, so the mock's default
        // zero-return is the right stand-in for "nothing was dismissed".
        resolutionRepository = Mockito.mock(SecurityFindingResolutionRepository.class);

        service = new SecurityFindingIngestService(repository, resolutionRepository);
    }

    private CloudAccount account() {
        CloudAccount account = new CloudAccount();
        account.setId(CLOUD_ACCOUNT_ID);
        account.setAwsAccountId(AWS_ACCOUNT);
        account.setProvider("AWS");
        return account;
    }

    private JsonNode envelope(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Source routing ──────────────────────────────────────────────────────

    @Test
    void recognisesTheThreeNativeSecuritySources() {
        assertTrue(SecurityFindingIngestService.isSecuritySource("aws.securityhub"));
        assertTrue(SecurityFindingIngestService.isSecuritySource("aws.guardduty"));
        assertTrue(SecurityFindingIngestService.isSecuritySource("aws.config"));
        assertTrue(!SecurityFindingIngestService.isSecuritySource("aws.ec2"));
        assertTrue(!SecurityFindingIngestService.isSecuritySource(null));
    }

    // ── GuardDuty ───────────────────────────────────────────────────────────

    /**
     * The regression this class exists for. GuardDuty severity is a float, and treating it as
     * a label flattens a 9.5 compromise to MEDIUM with no visible symptom.
     */
    @Test
    void guardDutyNumericSeverityIsNotFlattenedToMedium() {
        service.ingest(account(), envelope("{"
                + "\"source\":\"aws.guardduty\",\"region\":\"us-east-1\","
                + "\"time\":\"2026-08-21T10:00:00Z\","
                + "\"detail\":{\"id\":\"gd-1\",\"severity\":9.5,"
                + "\"type\":\"UnauthorizedAccess:EC2/SSHBruteForce\","
                + "\"title\":\"SSH brute force\",\"description\":\"Repeated failures\","
                + "\"resource\":{\"resourceType\":\"Instance\","
                + "\"instanceDetails\":{\"instanceId\":\"i-abc\"}}}}"));

        CloudSecurityFinding row = stored.get("gd-1");
        assertNotNull(row);
        assertEquals(SecuritySeverity.CRITICAL, row.getSeverity());
        assertEquals(CloudSecurityFinding.SOURCE_GUARDDUTY, row.getSource());
        assertEquals("i-abc", row.getResourceArn());
        assertEquals(CloudSecurityFinding.STATUS_ACTIVE, row.getStatus());
    }

    /** Redelivery of the same finding updates rather than duplicating. */
    @Test
    void redeliveryOfTheSameFindingDoesNotCreateASecondRow() {
        String event = "{\"source\":\"aws.guardduty\",\"region\":\"us-east-1\","
                + "\"detail\":{\"id\":\"gd-dup\",\"severity\":5.0,\"title\":\"Recon\","
                + "\"resource\":{\"resourceType\":\"Instance\"}}}";

        SecurityFindingIngestService.Result first = service.ingest(account(), envelope(event));
        SecurityFindingIngestService.Result second = service.ingest(account(), envelope(event));

        assertEquals(1, first.created);
        assertEquals(0, second.created);
        assertEquals(1, second.updated);
        assertEquals(1, stored.size());
    }

    @Test
    void archivedGuardDutyFindingResolves() {
        service.ingest(account(), envelope("{\"source\":\"aws.guardduty\","
                + "\"detail\":{\"id\":\"gd-2\",\"severity\":7.0,\"title\":\"Something\","
                + "\"resource\":{}}}"));
        SecurityFindingIngestService.Result result = service.ingest(account(), envelope(
                "{\"source\":\"aws.guardduty\","
                + "\"detail\":{\"id\":\"gd-2\",\"severity\":7.0,\"title\":\"Something\","
                + "\"resource\":{},\"service\":{\"archived\":true}}}"));

        assertEquals(1, result.resolved);
        assertEquals(CloudSecurityFinding.STATUS_RESOLVED, stored.get("gd-2").getStatus());
        assertNotNull(stored.get("gd-2").getResolvedAt());
    }

    // ── Security Hub ────────────────────────────────────────────────────────

    /** One Security Hub message can carry many findings. */
    @Test
    void securityHubBatchCreatesOneRowPerFinding() {
        SecurityFindingIngestService.Result result = service.ingest(account(), envelope("{"
                + "\"source\":\"aws.securityhub\",\"region\":\"eu-west-1\","
                + "\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-1\",\"Title\":\"S3 public\",\"Description\":\"Bucket is public\","
                + "\"Severity\":{\"Label\":\"CRITICAL\"},\"RecordState\":\"ACTIVE\","
                + "\"Resources\":[{\"Id\":\"arn:aws:s3:::my-bucket\",\"Type\":\"AwsS3Bucket\"}],"
                + "\"Compliance\":{\"SecurityControlId\":\"S3.2\"},\"ProductName\":\"Security Hub\","
                + "\"Remediation\":{\"Recommendation\":{\"Text\":\"Block public access\"}}},"
                + "{\"Id\":\"sh-2\",\"Title\":\"Root key\",\"Severity\":{\"Label\":\"HIGH\"},"
                + "\"RecordState\":\"ACTIVE\",\"Resources\":[{\"Id\":\"root\",\"Type\":\"AwsIamUser\"}]}"
                + "]}}"));

        assertEquals(2, result.created);
        assertEquals(SecuritySeverity.CRITICAL, stored.get("sh-1").getSeverity());
        assertEquals("S3.2", stored.get("sh-1").getControlId());
        assertEquals("Block public access", stored.get("sh-1").getRemediation());
        assertEquals("arn:aws:s3:::my-bucket", stored.get("sh-1").getResourceArn());
        assertEquals(SecuritySeverity.HIGH, stored.get("sh-2").getSeverity());
    }

    /**
     * Resolution comes from two independent places, and honouring only {@code RecordState}
     * would leave findings a user already triaged sitting open in XamOps.
     */
    @Test
    void securityHubResolvesFromBothRecordStateAndWorkflowStatus() {
        String active = "{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-%s\",\"Title\":\"T\",\"Severity\":{\"Label\":\"HIGH\"},"
                + "\"RecordState\":\"ACTIVE\",\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}";

        service.ingest(account(), envelope(String.format(active, "archived")));
        service.ingest(account(), envelope(String.format(active, "workflow")));

        service.ingest(account(), envelope("{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-archived\",\"Title\":\"T\",\"Severity\":{\"Label\":\"HIGH\"},"
                + "\"RecordState\":\"ARCHIVED\",\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}"));
        service.ingest(account(), envelope("{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-workflow\",\"Title\":\"T\",\"Severity\":{\"Label\":\"HIGH\"},"
                + "\"RecordState\":\"ACTIVE\",\"Workflow\":{\"Status\":\"RESOLVED\"},"
                + "\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}"));

        assertEquals(CloudSecurityFinding.STATUS_RESOLVED, stored.get("sh-archived").getStatus());
        assertEquals(CloudSecurityFinding.STATUS_RESOLVED, stored.get("sh-workflow").getStatus());
    }

    /**
     * A user's suppression outlives redelivery.
     *
     * <p>
     * The sequence matters and is the realistic one: the finding is open in XamOps, the user
     * suppresses it in Security Hub, then the provider republishes the finding without the
     * workflow field. Without the guard, that last step would silently reopen it, so
     * suppression would last only until the next republish — worse than offering none, because
     * the user believes they have dealt with it.
     */
    @Test
    void suppressionSurvivesRedelivery() {
        String active = "{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-sup\",\"Title\":\"T\",\"Severity\":{\"Label\":\"LOW\"},"
                + "\"RecordState\":\"ACTIVE\",\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}";

        service.ingest(account(), envelope(active));
        assertEquals(CloudSecurityFinding.STATUS_ACTIVE, stored.get("sh-sup").getStatus());

        service.ingest(account(), envelope("{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-sup\",\"Title\":\"T\",\"Severity\":{\"Label\":\"LOW\"},"
                + "\"RecordState\":\"ACTIVE\",\"Workflow\":{\"Status\":\"SUPPRESSED\"},"
                + "\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}"));
        assertEquals(CloudSecurityFinding.STATUS_SUPPRESSED, stored.get("sh-sup").getStatus());

        service.ingest(account(), envelope(active));
        assertEquals(CloudSecurityFinding.STATUS_SUPPRESSED, stored.get("sh-sup").getStatus(),
                "a redelivery must not undo a suppression the user chose");
    }

    /**
     * A finding that arrives already suppressed is not recorded at all.
     *
     * <p>
     * Deliberate: a customer who has triaged findings in the Security Hub console should not
     * have them reappear in XamOps the moment ingest is switched on. Storing an
     * already-closed row would add noise to the audit trail and nothing to the page.
     */
    @Test
    void findingThatArrivesAlreadySuppressedIsNotRecorded() {
        SecurityFindingIngestService.Result result = service.ingest(account(), envelope(
                "{\"source\":\"aws.securityhub\",\"detail\":{\"findings\":["
                + "{\"Id\":\"sh-presup\",\"Title\":\"T\",\"Severity\":{\"Label\":\"LOW\"},"
                + "\"RecordState\":\"ACTIVE\",\"Workflow\":{\"Status\":\"SUPPRESSED\"},"
                + "\"Resources\":[{\"Id\":\"r\",\"Type\":\"t\"}]}]}}"));

        assertEquals(0, result.created);
        assertTrue(stored.isEmpty());
    }

    // ── Config ──────────────────────────────────────────────────────────────

    /** Config is the one source that closes its own findings. */
    @Test
    void configNonCompliantOpensAndCompliantResolves() {
        String template = "{\"source\":\"aws.config\",\"region\":\"ap-south-1\","
                + "\"detail\":{\"configRuleName\":\"s3-bucket-public-read-prohibited\","
                + "\"resourceId\":\"my-bucket\",\"resourceType\":\"AWS::S3::Bucket\","
                + "\"awsRegion\":\"ap-south-1\","
                + "\"newEvaluationResult\":{\"complianceType\":\"%s\"}}}";

        SecurityFindingIngestService.Result opened =
                service.ingest(account(), envelope(String.format(template, "NON_COMPLIANT")));
        assertEquals(1, opened.created);
        assertEquals(1, stored.size());

        CloudSecurityFinding row = stored.values().iterator().next();
        assertEquals(CloudSecurityFinding.SOURCE_CONFIG, row.getSource());
        assertEquals(CloudSecurityFinding.STATUS_ACTIVE, row.getStatus());
        assertTrue(row.getTitle().contains("s3-bucket-public-read-prohibited"));

        SecurityFindingIngestService.Result closed =
                service.ingest(account(), envelope(String.format(template, "COMPLIANT")));
        assertEquals(1, closed.resolved);
        assertEquals(1, stored.size(), "resolution must update the row, not add one");
        assertEquals(CloudSecurityFinding.STATUS_RESOLVED,
                stored.values().iterator().next().getStatus());
    }

    @Test
    void configNotApplicableIsIgnored() {
        service.ingest(account(), envelope("{\"source\":\"aws.config\",\"detail\":{"
                + "\"configRuleName\":\"rule\",\"resourceId\":\"r\","
                + "\"newEvaluationResult\":{\"complianceType\":\"NOT_APPLICABLE\"}}}"));
        assertTrue(stored.isEmpty());
    }

    /**
     * A resolution for a finding we never saw is dropped rather than stored as an
     * already-closed row, which would only add noise to the audit trail.
     */
    @Test
    void resolutionForAnUnknownFindingIsNotRecorded() {
        SecurityFindingIngestService.Result result = service.ingest(account(), envelope(
                "{\"source\":\"aws.config\",\"detail\":{\"configRuleName\":\"rule\","
                + "\"resourceId\":\"r\",\"newEvaluationResult\":{\"complianceType\":\"COMPLIANT\"}}}"));
        assertEquals(0, result.created);
        assertEquals(0, result.resolved);
        assertTrue(stored.isEmpty());
    }

    /** A fixed-then-broken-again resource reads as new, and becomes notifiable again. */
    @Test
    void reopenedFindingCountsAsNewAndClearsTheNotifiedMarker() {
        String template = "{\"source\":\"aws.config\",\"detail\":{\"configRuleName\":\"rule\","
                + "\"resourceId\":\"r\",\"newEvaluationResult\":{\"complianceType\":\"%s\"}}}";

        service.ingest(account(), envelope(String.format(template, "NON_COMPLIANT")));
        CloudSecurityFinding row = stored.values().iterator().next();
        row.setNotifiedAt(java.time.Instant.now());

        service.ingest(account(), envelope(String.format(template, "COMPLIANT")));
        SecurityFindingIngestService.Result reopened =
                service.ingest(account(), envelope(String.format(template, "NON_COMPLIANT")));

        assertEquals(1, reopened.created, "a regression is news, not an update");
        assertEquals(CloudSecurityFinding.STATUS_ACTIVE, row.getStatus());
        assertNull(row.getNotifiedAt(), "a reopened finding must be eligible for notification again");
    }

    // ── XamOps detectors ────────────────────────────────────────────────────

    @Test
    void detectorBuildsAFindingFromALifecycleEvent() {
        SecurityFindingIngestService.Result result = service.ingestDetected(account(), envelope("{"
                + "\"source\":\"aws.s3\",\"eventName\":\"DeletePublicAccessBlock\","
                + "\"region\":\"us-east-1\",\"arns\":[\"arn:aws:s3:::exposed-bucket\"]}"));

        assertEquals(1, result.created);
        CloudSecurityFinding row = stored.values().iterator().next();
        assertEquals(CloudSecurityFinding.SOURCE_XAMOPS_DETECTOR, row.getSource());
        assertEquals(SecuritySeverity.CRITICAL, row.getSeverity());
        assertEquals("arn:aws:s3:::exposed-bucket", row.getResourceArn());
        assertEquals("DeletePublicAccessBlock", row.getControlId());
    }

    /**
     * Keyed on event plus resource, not on event id, so a repeat of the same
     * misconfiguration updates the open finding instead of stacking rows.
     */
    @Test
    void repeatedDetectionOfTheSameResourceUpdatesOneFinding() {
        String event = "{\"eventName\":\"DeletePublicAccessBlock\",\"region\":\"us-east-1\","
                + "\"arns\":[\"arn:aws:s3:::b\"]}";
        service.ingestDetected(account(), envelope(event));
        service.ingestDetected(account(), envelope(event));
        assertEquals(1, stored.size());
    }

    /**
     * The forwarder cannot always derive an ARN. Recording the finding against the region is
     * still actionable, and silence would be the worse outcome.
     */
    @Test
    void detectorStillRecordsWhenNoArnCouldBeResolved() {
        service.ingestDetected(account(), envelope(
                "{\"eventName\":\"DeleteTrail\",\"region\":\"eu-west-1\",\"arns\":[]}"));

        assertEquals(1, stored.size());
        CloudSecurityFinding row = stored.values().iterator().next();
        assertNull(row.getResourceArn());
        assertEquals("eu-west-1", row.getRegion());
        assertTrue(row.getDescription().contains("could not be identified"),
                "the user needs to know the resource is unidentified rather than assume it is fine");
    }

    @Test
    void unmatchedEventNameProducesNoFinding() {
        SecurityFindingIngestService.Result result = service.ingestDetected(account(), envelope(
                "{\"eventName\":\"RunInstances\",\"region\":\"us-east-1\",\"arns\":[\"arn:x\"]}"));
        assertEquals(0, result.created);
        assertTrue(stored.isEmpty());
    }

    // ── Exposure detectors through ingest ───────────────────────────────────

    /**
     * The end-to-end shape of the Phase 3 path: a security group opened to the world becomes a
     * critical finding whose resource comes from the event detail, not from an ARN.
     */
    @Test
    void exposureDetectorFindingIsStoredWithResourceFromTheDetail() {
        SecurityFindingIngestService.Result result = service.ingestDetected(account(), envelope("{"
                + "\"eventName\":\"AuthorizeSecurityGroupIngress\",\"region\":\"ap-south-1\","
                + "\"arns\":[],\"detail\":{\"eventName\":\"AuthorizeSecurityGroupIngress\","
                + "\"actor\":{\"userName\":\"arjun\"},"
                + "\"requestParameters\":{\"groupId\":\"sg-04e1f9\",\"ipPermissions\":{\"items\":[{"
                + "\"fromPort\":22,\"toPort\":22,"
                + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"0.0.0.0/0\"}]}}]}}}}"));

        assertEquals(1, result.created);
        CloudSecurityFinding row = stored.values().iterator().next();
        assertEquals(SecuritySeverity.CRITICAL, row.getSeverity());
        assertEquals(CloudSecurityFinding.SOURCE_XAMOPS_DETECTOR, row.getSource());
        assertEquals("sg-04e1f9", row.getResourceArn(),
                "the resource must come from the detail — no ARN appears anywhere in this event");
        assertEquals("ap-south-1", row.getRegion());
        assertTrue(row.getTitle().contains("SSH"));
    }

    /** "Changed by whom" is the first question a critical finding provokes. */
    @Test
    void actorFromTheEventIsRecorded() {
        service.ingestDetected(account(), envelope("{"
                + "\"eventName\":\"ModifyDBInstance\",\"region\":\"us-east-1\",\"arns\":[],"
                + "\"detail\":{\"actor\":{\"userName\":\"arjun\"},"
                + "\"requestParameters\":{\"dBInstanceIdentifier\":\"prod-db\","
                + "\"publiclyAccessible\":true}}}"));

        CloudSecurityFinding row = stored.values().iterator().next();
        assertEquals("Changed by arjun", row.getComplianceFramework());
    }

    /**
     * A scoped change produces nothing at all. This is the property that keeps the feature usable —
     * these event names occur constantly during normal work.
     */
    @Test
    void routineScopedChangeProducesNoFindingThroughIngest() {
        SecurityFindingIngestService.Result result = service.ingestDetected(account(), envelope("{"
                + "\"eventName\":\"AuthorizeSecurityGroupIngress\",\"region\":\"us-east-1\","
                + "\"arns\":[],\"detail\":{\"requestParameters\":{\"groupId\":\"sg-1\","
                + "\"ipPermissions\":{\"items\":[{\"fromPort\":443,\"toPort\":443,"
                + "\"ipRanges\":{\"items\":[{\"cidrIp\":\"10.0.0.0/8\"}]}}]}}}}"));

        assertEquals(0, result.created);
        assertTrue(stored.isEmpty());
    }

    /**
     * With a version 1 forwarder there is no detail, so exposure detectors cannot run and the event
     * correctly yields nothing rather than a guess.
     */
    @Test
    void exposureEventWithoutDetailYieldsNothing() {
        SecurityFindingIngestService.Result result = service.ingestDetected(account(), envelope("{"
                + "\"eventName\":\"AuthorizeSecurityGroupIngress\",\"region\":\"us-east-1\","
                + "\"arns\":[],\"detail\":null}"));

        assertEquals(0, result.created);
        assertTrue(stored.isEmpty());
    }

    /**
     * Two different resources exposed the same way are two findings, not one. Keying on the event
     * name alone would collapse an estate-wide problem into a single row.
     */
    @Test
    void sameExposureOnTwoResourcesProducesTwoFindings() {
        String template = "{\"eventName\":\"PutBucketAcl\",\"region\":\"us-east-1\",\"arns\":[],"
                + "\"detail\":{\"requestParameters\":{\"bucketName\":\"%s\","
                + "\"x-amz-acl\":\"public-read\"}}}";

        service.ingestDetected(account(), envelope(String.format(template, "bucket-a")));
        service.ingestDetected(account(), envelope(String.format(template, "bucket-b")));

        assertEquals(2, stored.size());
    }

    /** The name-only rules still work, and now resolve their resource from the detail. */
    @Test
    void nameOnlyDetectorNowNamesItsResource() {
        service.ingestDetected(account(), envelope("{"
                + "\"eventName\":\"DeleteTrail\",\"region\":\"eu-west-1\",\"arns\":[],"
                + "\"detail\":{\"requestParameters\":{\"name\":\"prod-audit-trail\"}}}"));

        CloudSecurityFinding row = stored.values().iterator().next();
        assertEquals("prod-audit-trail", row.getResourceArn(),
                "DeleteTrail names the trail in its parameters — findings used to say only a region");
        assertEquals(SecuritySeverity.CRITICAL, row.getSeverity());
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    /**
     * Malformed content must not throw. A parse failure that fails the message would have SQS
     * redeliver it three times and then DLQ it, and the same malformed shape would fail every
     * time — so it is logged and skipped instead.
     */
    @Test
    void malformedEventsAreSkippedRatherThanThrowing() {
        assertEquals(0, service.ingest(account(),
                envelope("{\"source\":\"aws.guardduty\",\"detail\":{}}")).created);
        assertEquals(0, service.ingest(account(),
                envelope("{\"source\":\"aws.securityhub\",\"detail\":{}}")).created);
        assertEquals(0, service.ingest(account(),
                envelope("{\"source\":\"aws.config\",\"detail\":{}}")).created);
        assertEquals(0, service.ingest(account(), envelope("{}")).created);
        assertTrue(stored.isEmpty());
    }

    /** Over-long provider strings are truncated to the column width rather than failing insert. */
    @Test
    void oversizedTitlesAreTruncatedToFitTheColumn() {
        String longTitle = "x".repeat(3000);
        service.ingest(account(), envelope("{\"source\":\"aws.guardduty\",\"detail\":{"
                + "\"id\":\"gd-long\",\"severity\":5.0,\"title\":\"" + longTitle + "\","
                + "\"resource\":{}}}"));
        assertEquals(1024, stored.get("gd-long").getTitle().length());
    }
}
