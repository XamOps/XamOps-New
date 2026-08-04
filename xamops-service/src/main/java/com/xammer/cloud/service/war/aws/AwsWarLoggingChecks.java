package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.guardduty.model.DetectorFeatureConfigurationResult;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Logging, monitoring and threat-detection controls (advisory rows 38-45).
 *
 * <p>
 * This group decides how long an incident runs before anyone notices. The advisory
 * is blunt about it: without proactive alerting, the first signal of a mining attack
 * is the monthly invoice, and by then the charges have been processed. A failure here
 * does not create the attack path — it removes the chance to stop it mid-flight.
 */
@Component
public class AwsWarLoggingChecks {

    /**
     * GuardDuty protection plans beyond the base detector. Malware and EKS coverage
     * are what catch mining payloads on instances and hijacked cluster nodes.
     */
    private static final Set<String> EXTENDED_FEATURES = Set.of(
            "EBS_MALWARE_PROTECTION", "S3_DATA_EVENTS", "EKS_AUDIT_LOGS", "RDS_LOGIN_EVENTS",
            "RUNTIME_MONITORING", "LAMBDA_NETWORK_LOGS");

    /** CloudTrail events worth an alarm, per advisory row 41. */
    private static final List<String> CRITICAL_EVENT_HINTS = List.of(
            "root", "consolelogin", "iam", "securitygroup", "cloudtrail", "unauthorized", "networkacl");

    private final AwsClientProvider clients;

    public AwsWarLoggingChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String primaryRegion = ctx.regions().isEmpty() ? "us-east-1" : ctx.regions().get(0);

        checkCloudTrail(ctx, primaryRegion);
        checkCriticalEventAlarms(ctx, primaryRegion);
        for (String region : ctx.regions()) {
            checkGuardDuty(ctx, region);
            checkSecurityHub(ctx, region);
        }
    }

    /** AWS-38 (org-wide multi-region), AWS-39 (centralised delivery), AWS-40 (validation + retention). */
    private void checkCloudTrail(WarScanContext ctx, String region) {
        ctx.guard("cloudtrail:DescribeTrails", List.of("AWS-38", "AWS-39", "AWS-40"), () -> {
            CloudTrailClient client = clients.getCloudTrailClient(ctx.account(), region);
            List<Trail> trails = client.describeTrails().trailList();

            if (trails.isEmpty()) {
                List.of("AWS-38", "AWS-39", "AWS-40").forEach(id -> ctx.put(id, WarCheckResult.fail(
                        "No CloudTrail trail exists — the account has no audit trail at all")));
                return;
            }

            boolean orgMultiRegion = false;
            boolean centralised = false;
            List<String> validationGaps = new ArrayList<>();
            List<String> deliveryGaps = new ArrayList<>();

            for (Trail trail : trails) {
                boolean logging = ctx.quiet(false, () -> client
                        .getTrailStatus(r -> r.name(trail.trailARN())).isLogging());
                if (!logging) {
                    deliveryGaps.add(trail.name() + " exists but is not logging");
                    continue;
                }
                if (Boolean.TRUE.equals(trail.isMultiRegionTrail())
                        && Boolean.TRUE.equals(trail.isOrganizationTrail())) {
                    orgMultiRegion = true;
                }
                if (trail.s3BucketName() != null && trail.cloudWatchLogsLogGroupArn() != null) {
                    centralised = true;
                } else if (trail.cloudWatchLogsLogGroupArn() == null) {
                    deliveryGaps.add(trail.name() + " is not streaming to CloudWatch Logs, so nothing "
                            + "can alarm on its events in real time");
                }
                if (!Boolean.TRUE.equals(trail.logFileValidationEnabled())) {
                    validationGaps.add(trail.name() + " has log file validation disabled — tampering "
                            + "would go undetected");
                }
            }

            // A multi-region trail without organization scope still covers this account.
            boolean anyMultiRegion = trails.stream()
                    .anyMatch(t -> Boolean.TRUE.equals(t.isMultiRegionTrail()));

            if (orgMultiRegion) {
                ctx.put("AWS-38", WarCheckResult.pass(
                        "An organization-wide, multi-region trail is active"));
            } else if (anyMultiRegion) {
                ctx.put("AWS-38", WarCheckResult.warn("A multi-region trail exists but it is not an "
                        + "organization trail — new member accounts are not covered automatically"));
            } else {
                ctx.put("AWS-38", WarCheckResult.fail("No multi-region trail — activity in unmonitored "
                        + "Regions is invisible, which is exactly where miners launch"));
            }

            ctx.put("AWS-39", centralised && deliveryGaps.isEmpty()
                    ? WarCheckResult.pass("Trail logs reach both S3 and CloudWatch Logs")
                    : WarCheckResult.fail("CloudTrail delivery is incomplete").withEvidence(deliveryGaps));

            ctx.put("AWS-40", WarCheckResult.of(validationGaps,
                    "CloudTrail log file validation is disabled",
                    "Log file validation is enabled on all trails"));
        });
    }

    /** AWS-41: alarms on root sign-in, IAM changes, security group changes, trail disablement. */
    private void checkCriticalEventAlarms(WarScanContext ctx, String region) {
        ctx.guard("cloudwatch:DescribeAlarms", List.of("AWS-41"), () -> {
            CloudWatchClient cw = clients.getCloudWatchClient(ctx.account(), region);

            List<String> alarmNames = cw.describeAlarmsPaginator().metricAlarms().stream()
                    .map(a -> (a.alarmName() + " " + (a.alarmDescription() == null ? "" : a.alarmDescription())
                            + " " + (a.metricName() == null ? "" : a.metricName()))
                            .toLowerCase(Locale.ROOT))
                    .toList();

            List<String> matched = CRITICAL_EVENT_HINTS.stream()
                    .filter(hint -> alarmNames.stream().anyMatch(name -> name.contains(hint)))
                    .toList();

            if (alarmNames.isEmpty()) {
                ctx.put("AWS-41", WarCheckResult.fail("No CloudWatch alarms in " + region + " — nothing "
                        + "will page anyone while an attack is in progress").withRegion(region));
            } else if (matched.isEmpty()) {
                ctx.put("AWS-41", WarCheckResult.fail(String.format(
                                "%d alarms exist in %s but none watch root sign-in, IAM changes, security "
                                        + "group changes or CloudTrail disablement",
                                alarmNames.size(), region))
                        .withRegion(region));
            } else {
                ctx.put("AWS-41", WarCheckResult.pass(String.format(
                        "Alarms cover %d of %d critical event categories in %s",
                        matched.size(), CRITICAL_EVENT_HINTS.size(), region)).withRegion(region));
            }
        });
    }

    /** AWS-42 (GuardDuty enabled) and AWS-43 (extended protection plans). */
    private void checkGuardDuty(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "guardduty:ListDetectors",
                List.of("AWS-42", "AWS-43"), () -> {
            GuardDutyClient gd = clients.getGuardDutyClient(ctx.account(), region);
            List<String> detectorIds = gd.listDetectors(r -> {
            }).detectorIds();

            if (detectorIds.isEmpty()) {
                ctx.merge("AWS-42", WarCheckResult.fail("GuardDuty is not enabled in " + region
                        + " — the CryptoCurrency finding type that detects mining-pool traffic is not "
                        + "running").withRegion(region));
                ctx.merge("AWS-43", WarCheckResult.fail(
                        "GuardDuty is off in " + region + ", so no protection plans are active")
                        .withRegion(region));
                return;
            }

            String detectorId = detectorIds.get(0);
            var detector = gd.getDetector(r -> r.detectorId(detectorId));
            boolean enabled = "ENABLED".equalsIgnoreCase(detector.statusAsString());

            ctx.merge("AWS-42", enabled
                    ? WarCheckResult.pass("GuardDuty is enabled in " + region)
                    : WarCheckResult.fail("GuardDuty detector in " + region + " is suspended")
                            .withRegion(region));

            List<String> missing = new ArrayList<>();
            List<DetectorFeatureConfigurationResult> features = detector.features();
            if (features == null || features.isEmpty()) {
                missing.add(region + " — no extended protection plans reported");
            } else {
                Set<String> active = features.stream()
                        .filter(f -> "ENABLED".equalsIgnoreCase(f.statusAsString()))
                        .map(f -> f.nameAsString() == null ? "" : f.nameAsString().toUpperCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                for (String wanted : EXTENDED_FEATURES) {
                    if (!active.contains(wanted)) {
                        missing.add(region + " — " + wanted + " protection is off");
                    }
                }
            }

            ctx.merge("AWS-43", WarCheckResult.of(missing,
                    "GuardDuty extended protection plans are incomplete",
                    "All GuardDuty protection plans are enabled").withRegion(region));
        });
    }

    /** AWS-44: Security Hub as the single place findings land. */
    private void checkSecurityHub(WarScanContext ctx, String region) {
        ctx.guardRegion(region, "securityhub:GetEnabledStandards",
                List.of("AWS-44"), () -> {
            SecurityHubClient hub = clients.getSecurityHubClient(ctx.account(), region);
            var standards = hub.getEnabledStandards(r -> {
            }).standardsSubscriptions();

            if (standards.isEmpty()) {
                ctx.merge("AWS-44", WarCheckResult.fail("Security Hub has no enabled standards in " + region
                        + " — findings from GuardDuty, Macie and Config are never correlated")
                        .withRegion(region));
                return;
            }
            ctx.merge("AWS-44", WarCheckResult.pass(String.format(
                            "%d Security Hub standard(s) enabled in %s", standards.size(), region))
                    .withRegion(region));
        });
    }
}
