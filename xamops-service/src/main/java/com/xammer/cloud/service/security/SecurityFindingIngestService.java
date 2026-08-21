package com.xammer.cloud.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.repository.CloudSecurityFindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Turns one EventBridge envelope into security findings.
 *
 * <p>
 * The four shapes handled here are genuinely different, which is why this is a parser rather
 * than a mapper:
 * <ul>
 * <li><b>Security Hub</b> sends {@code detail.findings[]} — one message can carry many
 * findings — in ASFF, with severity as a label and state split across {@code RecordState} and
 * {@code Workflow.Status}.
 * <li><b>GuardDuty</b> sends a single finding with severity as a <em>float</em> and
 * resolution expressed as {@code service.archived}.
 * <li><b>Config</b> sends a compliance transition, where the finding and its resolution are
 * the same event shape with a different {@code complianceType}.
 * <li><b>XamOps detectors</b> have no detail at all — the forwarder strips it for
 * non-security sources — so they are built from {@code eventName} plus {@code arns}.
 * </ul>
 *
 * <p>
 * Ingest is idempotent by {@code (cloudAccountId, findingId)}. That is load-bearing rather
 * than defensive: SQS is at-least-once, GuardDuty re-publishes subsequent occurrences of a
 * finding on its own cadence (six hours by default), and Security Hub re-imports on every
 * state change. Without keying on the provider's id, an account under sustained attack would
 * accumulate a row per redelivery.
 */
@Service
public class SecurityFindingIngestService {

    private static final Logger log = LoggerFactory.getLogger(SecurityFindingIngestService.class);

    public static final String SRC_SECURITY_HUB = "aws.securityhub";
    public static final String SRC_GUARDDUTY = "aws.guardduty";
    public static final String SRC_CONFIG = "aws.config";

    /** What one envelope did, so the caller can decide whether to notify. */
    public static final class Result {
        public int created;
        public int updated;
        public int resolved;

        public boolean changed() {
            return created > 0 || updated > 0 || resolved > 0;
        }

        void merge(Outcome outcome) {
            switch (outcome) {
                case CREATED:
                    created++;
                    break;
                case UPDATED:
                    updated++;
                    break;
                case RESOLVED:
                    resolved++;
                    break;
                default:
                    break;
            }
        }
    }

    enum Outcome {
        CREATED, UPDATED, RESOLVED, IGNORED
    }

    private final CloudSecurityFindingRepository repository;

    public SecurityFindingIngestService(CloudSecurityFindingRepository repository) {
        this.repository = repository;
    }

    /** Whether this envelope is one of the AWS-native security sources. */
    public static boolean isSecuritySource(String source) {
        return SRC_SECURITY_HUB.equals(source) || SRC_GUARDDUTY.equals(source)
                || SRC_CONFIG.equals(source);
    }

    /**
     * Applies one envelope.
     *
     * <p>
     * Never throws on malformed content. A finding we cannot parse is logged and skipped
     * rather than failing the message, because a parse failure that fails the message would
     * have SQS redeliver it three times and then DLQ it — and the same malformed shape would
     * fail again every time. Genuine infrastructure failures still propagate, since those
     * <em>are</em> worth retrying.
     */
    @Transactional
    public Result ingest(CloudAccount account, JsonNode envelope) {
        Result result = new Result();
        String source = envelope.path("source").asText("");
        JsonNode detail = envelope.path("detail");

        try {
            if (SRC_SECURITY_HUB.equals(source)) {
                JsonNode findings = detail.path("findings");
                if (findings.isArray()) {
                    for (JsonNode finding : findings) {
                        result.merge(applySecurityHub(account, finding, envelope));
                    }
                } else {
                    log.debug("[SecFinding] Security Hub event without a findings array, skipping");
                }
            } else if (SRC_GUARDDUTY.equals(source)) {
                result.merge(applyGuardDuty(account, detail, envelope));
            } else if (SRC_CONFIG.equals(source)) {
                result.merge(applyConfig(account, detail, envelope));
            }
        } catch (Exception e) {
            log.warn("[SecFinding] Could not parse {} event for account {}: {}",
                    source, account.getAwsAccountId(), e.getMessage());
        }
        return result;
    }

    /**
     * Applies a XamOps-derived finding from a lifecycle event.
     *
     * <p>
     * Separate entry point from {@link #ingest} because the input is different in kind: there
     * is no {@code detail} to parse, only the event name the forwarder preserved and whatever
     * ARN it managed to resolve.
     */
    @Transactional
    public Result ingestDetected(CloudAccount account, JsonNode envelope) {
        Result result = new Result();
        String eventName = envelope.path("eventName").asText("");
        if (eventName.isBlank()) {
            return result;
        }

        String region = envelope.path("region").asText(null);
        JsonNode detail = envelope.path("detail");
        String arn = firstArn(envelope);

        // Exposure detectors first. They inspect what the change actually did, so they are the
        // ones that can distinguish a port opened to the office VPN from one opened to the
        // internet — and an event they decline is one that must not produce a finding.
        Optional<DerivedFinding> derived = ExposureDetector.detect(eventName, detail);

        // Then the name-only rules, for events that are dangerous regardless of arguments.
        if (derived.isEmpty()) {
            derived = SecurityFindingDetector.ruleFor(eventName)
                    .map(rule -> new DerivedFinding(
                            eventName + ":" + (arn != null ? arn : "region:" + region),
                            rule.title, rule.severity, rule.resourceType,
                            // Prefer the identifier the event itself carried: these events name
                            // the resource they changed even when no ARN appears in the payload,
                            // which is why findings used to say only which region.
                            resourceIdFromDetail(detail, arn),
                            rule.description, rule.remediation));
        }
        if (derived.isEmpty()) {
            return result;
        }
        DerivedFinding finding = derived.get();

        // Keyed on the detector's key rather than on the event id. Keying on the event id would
        // make every repeat of the same misconfiguration a new row; this way a second exposure of
        // the same bucket updates the finding already open, while the same event against a
        // different bucket is correctly separate.
        String findingId = "xamops:" + eventName + ":" + sha256Short(finding.key);

        String resource = finding.resourceId != null ? finding.resourceId : arn;
        String description = resource != null
                ? finding.description
                : finding.description + " The affected resource could not be identified from the "
                        + "event, so review recent changes in this region.";

        result.merge(upsert(account, findingId, CloudSecurityFinding.SOURCE_XAMOPS_DETECTOR,
                finding.severity, finding.title, description, resource, finding.resourceType,
                region, actorOf(detail), eventName, finding.remediation,
                eventTime(envelope), null));
        return result;
    }

    /**
     * Who made the change, when the forwarder captured it.
     *
     * <p>
     * Stored in the framework column because it is the one free text field the existing Security
     * Center row already renders. "Changed by whom" is the first question a critical finding
     * provokes, and without it the user has to go and correlate CloudTrail by hand.
     */
    private static String actorOf(JsonNode detail) {
        if (detail == null || detail.isMissingNode()) {
            return null;
        }
        JsonNode actor = detail.path("actor");
        String name = actor.path("userName").asText(null);
        if (name != null && !name.isBlank() && !"null".equals(name)) {
            return "Changed by " + name;
        }
        String arn = actor.path("arn").asText(null);
        if (arn != null && !arn.isBlank() && !"null".equals(arn)) {
            return "Changed by " + arn;
        }
        return null;
    }

    /**
     * Resource identifier from the slimmed detail, falling back to the derived ARN.
     *
     * <p>
     * The named-resource fields are checked in a fixed order because several can be present at
     * once; the first match is the subject of the event.
     */
    private static String resourceIdFromDetail(JsonNode detail, String arn) {
        if (detail == null || detail.isMissingNode()) {
            return arn;
        }
        JsonNode params = detail.path("requestParameters");
        for (String field : new String[] { "bucketName", "name", "trailName", "groupId",
                "dBInstanceIdentifier", "userName", "roleName", "keyId", "configRuleName",
                "detectorId", "flowLogIds", "functionName" }) {
            String value = params.path(field).asText(null);
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return arn;
    }

    // ── Per-source parsers ──────────────────────────────────────────────────

    /**
     * Security Hub ASFF finding.
     *
     * <p>
     * Resolution is deliberately read from two places. {@code RecordState=ARCHIVED} is Security
     * Hub's own lifecycle, while {@code Workflow.Status} is what a human set — RESOLVED or
     * SUPPRESSED. Honouring only the first would leave findings a user has already triaged
     * sitting open in XamOps, which is the fastest way to make the page untrustworthy.
     */
    private Outcome applySecurityHub(CloudAccount account, JsonNode finding, JsonNode envelope) {
        String findingId = finding.path("Id").asText(null);
        if (findingId == null || findingId.isBlank()) {
            return Outcome.IGNORED;
        }

        String recordState = finding.path("RecordState").asText("ACTIVE");
        String workflow = finding.path("Workflow").path("Status").asText("");
        boolean archived = "ARCHIVED".equalsIgnoreCase(recordState)
                || "RESOLVED".equalsIgnoreCase(workflow);
        boolean suppressed = "SUPPRESSED".equalsIgnoreCase(workflow);

        JsonNode resource = finding.path("Resources").isArray() && finding.path("Resources").size() > 0
                ? finding.path("Resources").get(0)
                : null;

        String severity = SecuritySeverity.normalize(
                finding.path("Severity").path("Label").asText(null));

        // A compliance-linked control gives us a framework and control id; a GuardDuty
        // finding routed through Security Hub does not, so both are optional.
        String controlId = firstNonBlank(
                finding.path("Compliance").path("SecurityControlId").asText(null),
                finding.path("ProductFields").path("ControlId").asText(null),
                finding.path("GeneratorId").asText(null));

        String status = suppressed ? CloudSecurityFinding.STATUS_SUPPRESSED
                : archived ? CloudSecurityFinding.STATUS_RESOLVED
                        : CloudSecurityFinding.STATUS_ACTIVE;

        return upsert(account, findingId, CloudSecurityFinding.SOURCE_SECURITY_HUB,
                severity,
                finding.path("Title").asText("Security Hub finding"),
                finding.path("Description").asText(null),
                resource == null ? null : resource.path("Id").asText(null),
                resource == null ? null : resource.path("Type").asText(null),
                firstNonBlank(finding.path("Region").asText(null), envelope.path("region").asText(null)),
                finding.path("ProductName").asText(null),
                controlId,
                finding.path("Remediation").path("Recommendation").path("Text").asText(null),
                eventTime(envelope), status);
    }

    /**
     * GuardDuty finding.
     *
     * <p>
     * The severity trap: GuardDuty scores findings {@code 1.0}–{@code 10.0} rather than
     * labelling them. Reading {@code detail.severity} as text and normalising the string
     * matches nothing and flattens every GuardDuty finding to MEDIUM — including an active
     * compromise scored 9.5. Hence {@link SecuritySeverity#fromGuardDutyScore}.
     */
    private Outcome applyGuardDuty(CloudAccount account, JsonNode detail, JsonNode envelope) {
        String findingId = detail.path("id").asText(null);
        if (findingId == null || findingId.isBlank()) {
            return Outcome.IGNORED;
        }

        JsonNode resource = detail.path("resource");
        String resourceType = resource.path("resourceType").asText(null);

        boolean archived = detail.path("service").path("archived").asBoolean(false);

        return upsert(account, findingId, CloudSecurityFinding.SOURCE_GUARDDUTY,
                SecuritySeverity.fromGuardDutyScore(detail.path("severity").asDouble(0.0)),
                detail.path("title").asText(
                        firstNonBlank(detail.path("type").asText(null), "GuardDuty finding")),
                detail.path("description").asText(null),
                guardDutyResourceArn(resource),
                resourceType,
                firstNonBlank(detail.path("region").asText(null), envelope.path("region").asText(null)),
                "GuardDuty",
                detail.path("type").asText(null),
                null,
                eventTime(envelope),
                archived ? CloudSecurityFinding.STATUS_RESOLVED : CloudSecurityFinding.STATUS_ACTIVE);
    }

    /**
     * Config compliance transition.
     *
     * <p>
     * The finding and its remediation arrive as the same event shape with a different
     * {@code complianceType}, so a COMPLIANT transition resolves the row a previous
     * NON_COMPLIANT transition opened. This is the one source that closes its own findings,
     * which is why Config being enabled materially improves the signal quality of the page.
     */
    private Outcome applyConfig(CloudAccount account, JsonNode detail, JsonNode envelope) {
        String ruleName = detail.path("configRuleName").asText(null);
        String resourceId = detail.path("resourceId").asText(null);
        if (ruleName == null || ruleName.isBlank()) {
            return Outcome.IGNORED;
        }

        String complianceType = detail.path("newEvaluationResult")
                .path("complianceType").asText("");
        if ("NOT_APPLICABLE".equalsIgnoreCase(complianceType)) {
            return Outcome.IGNORED;
        }
        boolean compliant = "COMPLIANT".equalsIgnoreCase(complianceType);

        String region = firstNonBlank(detail.path("awsRegion").asText(null),
                envelope.path("region").asText(null));
        // Config gives no stable finding id, so one is derived from what identifies the
        // evaluation: the rule plus the resource it evaluated.
        String findingId = "config:" + ruleName + ":" + sha256Short(
                (resourceId == null ? "" : resourceId) + "|" + (region == null ? "" : region));

        return upsert(account, findingId, CloudSecurityFinding.SOURCE_CONFIG,
                // Config does not carry severity. MEDIUM rather than a guess derived from the
                // rule name, which would be a fiction dressed up as an assessment.
                SecuritySeverity.MEDIUM,
                "Config rule not satisfied: " + ruleName,
                "AWS Config evaluated " + (resourceId == null ? "a resource" : resourceId)
                        + " against rule " + ruleName + " and the result was " + complianceType + ".",
                resourceId,
                detail.path("resourceType").asText(null),
                region,
                "AWS Config",
                ruleName,
                null,
                eventTime(envelope),
                compliant ? CloudSecurityFinding.STATUS_RESOLVED : CloudSecurityFinding.STATUS_ACTIVE);
    }

    // ── Upsert ──────────────────────────────────────────────────────────────

    private Outcome upsert(CloudAccount account, String findingId, String source, String severity,
            String title, String description, String resourceArn, String resourceType,
            String region, String framework, String controlId, String remediation,
            Instant eventTime, String status) {

        Instant now = Instant.now();
        String effectiveStatus = status == null ? CloudSecurityFinding.STATUS_ACTIVE : status;

        Optional<CloudSecurityFinding> existingOpt =
                repository.findByCloudAccountIdAndFindingId(account.getId(), findingId);

        if (existingOpt.isEmpty()) {
            if (!CloudSecurityFinding.STATUS_ACTIVE.equals(effectiveStatus)) {
                // A resolution for something we never recorded. Common and harmless: the
                // finding predates the pipeline, or was fixed between two deliveries. Storing
                // a row that arrives already-resolved would put noise in the audit trail.
                return Outcome.IGNORED;
            }
            CloudSecurityFinding row = new CloudSecurityFinding();
            row.setCloudAccountId(account.getId());
            row.setAwsAccountId(account.getAwsAccountId());
            row.setFindingId(findingId);
            row.setSource(source);
            row.setFirstSeenAt(now);
            row.setCreatedAt(now);
            copyMutable(row, severity, title, description, resourceArn, resourceType, region,
                    framework, controlId, remediation, eventTime, effectiveStatus, now);
            repository.save(row);
            log.info("[SecFinding] {} {} finding for account {} — {}",
                    severity, source, account.getAwsAccountId(), title);
            return Outcome.CREATED;
        }

        CloudSecurityFinding row = existingOpt.get();
        boolean wasActive = CloudSecurityFinding.STATUS_ACTIVE.equals(row.getStatus());
        boolean nowActive = CloudSecurityFinding.STATUS_ACTIVE.equals(effectiveStatus);

        // A user's SUPPRESSED decision is not overwritten by a redelivery of the same
        // finding. Without this, suppressing a finding in XamOps would last only until
        // GuardDuty republished it, which is worse than having no suppression at all.
        if (CloudSecurityFinding.STATUS_SUPPRESSED.equals(row.getStatus())
                && !CloudSecurityFinding.STATUS_SUPPRESSED.equals(effectiveStatus)) {
            row.setLastSeenAt(now);
            repository.save(row);
            return Outcome.UPDATED;
        }

        copyMutable(row, severity, title, description, resourceArn, resourceType, region,
                framework, controlId, remediation, eventTime, effectiveStatus, now);
        repository.save(row);

        if (wasActive && !nowActive) {
            log.info("[SecFinding] Resolved {} finding for account {} — {}",
                    source, account.getAwsAccountId(), title);
            return Outcome.RESOLVED;
        }
        if (!wasActive && nowActive) {
            // Regressions read as new: a bucket made public again is news, not an update to
            // a finding the user already closed.
            row.setFirstSeenAt(now);
            row.setNotifiedAt(null);
            repository.save(row);
            log.info("[SecFinding] Reopened {} finding for account {} — {}",
                    source, account.getAwsAccountId(), title);
            return Outcome.CREATED;
        }
        return Outcome.UPDATED;
    }

    private void copyMutable(CloudSecurityFinding row, String severity, String title,
            String description, String resourceArn, String resourceType, String region,
            String framework, String controlId, String remediation, Instant eventTime,
            String status, Instant now) {
        row.setSeverity(severity);
        row.setTitle(truncate(title, 1024));
        row.setDescription(description);
        row.setResourceArn(truncate(resourceArn, 2048));
        row.setResourceType(truncate(resourceType, 256));
        row.setRegion(truncate(region, 64));
        row.setComplianceFramework(truncate(framework, 256));
        row.setControlId(truncate(controlId, 256));
        row.setRemediation(remediation);
        row.setEventTime(eventTime);
        row.setStatus(status);
        row.setLastSeenAt(now);
        if (!CloudSecurityFinding.STATUS_ACTIVE.equals(status)) {
            row.setResolvedAt(now);
        } else {
            row.setResolvedAt(null);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Best-effort ARN out of a GuardDuty resource block.
     *
     * <p>
     * GuardDuty describes the affected resource differently per type and often gives an id
     * rather than an ARN. Returning the id is still useful — it is what the user searches for
     * — and better than dropping the association entirely.
     */
    private static String guardDutyResourceArn(JsonNode resource) {
        String[] candidates = {
                resource.path("s3BucketDetails").path(0).path("arn").asText(null),
                resource.path("instanceDetails").path("instanceId").asText(null),
                resource.path("eksClusterDetails").path("arn").asText(null),
                resource.path("rdsDbInstanceDetails").path("dbInstanceIdentifier").asText(null),
                resource.path("lambdaDetails").path("functionArn").asText(null),
                resource.path("accessKeyDetails").path("userName").asText(null),
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && !"null".equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstArn(JsonNode envelope) {
        JsonNode arns = envelope.path("arns");
        if (arns.isArray()) {
            for (JsonNode arn : arns) {
                String value = arn.asText(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Instant eventTime(JsonNode envelope) {
        String time = envelope.path("time").asText(null);
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * Short SHA-256, used to build stable ids for sources that supply none.
     *
     * <p>
     * Hashed rather than concatenated because the raw components can exceed the column width
     * and can contain characters that make the id awkward to handle; the hash is fixed-width
     * and stable across restarts, which is all the uniqueness constraint needs.
     */
    static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory on every JVM; if it is genuinely missing, a stable
            // fallback keeps ingest working rather than losing the finding.
            return Integer.toHexString(value.hashCode());
        }
    }
}
